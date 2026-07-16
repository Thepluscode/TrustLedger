package com.trustledger.app;

import com.trustledger.app.ExternalPaymentService.ExternalPaymentResponse;
import com.trustledger.app.ExternalPaymentService.ExternalTransferRequest;
import com.trustledger.core.fraud.FraudContext;
import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.OpenBankingCallbackEventEntity;
import com.trustledger.persistence.entity.PaymentConsentEntity;
import com.trustledger.persistence.repo.OpenBankingCallbackEventRepository;
import com.trustledger.persistence.repo.PaymentConsentRepository;
import com.trustledger.rails.ConsentStatus;
import com.trustledger.rails.OpenBankingSandboxAdapter;
import com.trustledger.security.ForbiddenException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Open Banking-shaped consent + authorisation flow. Create → redirect to bank → verified callback
 * (state + replay protection + redirect allowlist) → submit (reserves funds via the external rail).
 * No production bank access — this is the regulatory-safe sandbox path (see docs/REGULATORY_BOUNDARIES.md).
 */
@Service
public class ConsentService {

    public record CreateConsentResult(String consentReference, String authorisationUrl, String status) {}
    public record CallbackResult(String consentReference, String status) {}

    public static class ConsentException extends RuntimeException {
        public ConsentException(String m) { super(m); }
    }

    private final PaymentConsentRepository consents;
    private final OpenBankingCallbackEventRepository callbacks;
    private final OpenBankingSandboxAdapter adapter;
    private final ExternalPaymentService externalPayments;
    private final List<String> redirectAllowlist;
    private final long consentTtlSeconds;

    public ConsentService(PaymentConsentRepository consents, OpenBankingCallbackEventRepository callbacks,
                          OpenBankingSandboxAdapter adapter, ExternalPaymentService externalPayments,
                          @Value("${trustledger.openbanking.redirect-allowlist:https://app.trustledger.local,http://localhost:3000}") String allowlist,
                          @Value("${trustledger.openbanking.consent-ttl-seconds:900}") long consentTtlSeconds) {
        this.consents = consents;
        this.callbacks = callbacks;
        this.adapter = adapter;
        this.externalPayments = externalPayments;
        this.redirectAllowlist = Arrays.stream(allowlist.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.consentTtlSeconds = consentTtlSeconds;
    }

    @Transactional
    public CreateConsentResult createConsent(UUID tenantId, UUID userId, UUID sourceAccountId, UUID beneficiaryAccountId,
                                             BigDecimal amount, String currency, String redirectUrl) {
        if (redirectUrl == null || redirectAllowlist.stream().noneMatch(redirectUrl::startsWith)) {
            throw new IllegalArgumentException("redirect_url is not in the allowlist");
        }
        String consentRef = adapter.registerDomesticPaymentConsent();
        String state = "st_" + UUID.randomUUID();
        String nonce = "nc_" + UUID.randomUUID();
        consents.save(new PaymentConsentEntity(UUID.randomUUID(), tenantId, userId, OpenBankingSandboxAdapter.PROVIDER,
            consentRef, state, nonce, ConsentStatus.AWAITING_AUTHORISATION, sourceAccountId, beneficiaryAccountId,
            amount, currency, redirectUrl, Instant.now().plus(consentTtlSeconds, ChronoUnit.SECONDS)));
        return new CreateConsentResult(consentRef, adapter.authorisationUrl(consentRef, state, nonce),
            ConsentStatus.AWAITING_AUTHORISATION);
    }

    /** Verified bank redirect. State is one-time (replay rejected); the consent reference must match. */
    @Transactional
    public CallbackResult handleCallback(String state, String consentReference, String result) {
        if (state == null || consentReference == null) throw new IllegalArgumentException("missing state/consent_ref");
        if (callbacks.existsByStateToken(state)) {
            throw new ConsentException("Callback replay detected for state " + state);
        }
        PaymentConsentEntity consent = consents.findByStateToken(state)
            .orElseThrow(() -> new ConsentException("Unknown state token"));
        if (!consent.getConsentReference().equals(consentReference)) {
            throw new ConsentException("State/consent reference mismatch");
        }
        callbacks.save(new OpenBankingCallbackEventEntity(UUID.randomUUID(), consentReference, state, result, true));

        if (Instant.now().isAfter(consent.getExpiresAt())) {
            consent.setStatus(ConsentStatus.EXPIRED);
        } else if ("AUTHORISED".equalsIgnoreCase(result)) {
            consent.setStatus(ConsentStatus.AUTHORISED);
            consent.setAuthorisedAt(Instant.now());
        } else {
            consent.setStatus(ConsentStatus.REJECTED);
        }
        return new CallbackResult(consentReference, consent.getStatus());
    }

    /** Submit an authorised consent through an explicit, auditable sandbox provider route. */
    @Transactional
    public ExternalPaymentResponse submit(UUID tenantId, String consentReference, String scenario) {
        PaymentConsentEntity consent = consents.findByConsentReference(consentReference)
            .orElseThrow(() -> new IllegalArgumentException("Consent not found: " + consentReference));
        if (!consent.getTenantId().equals(tenantId)) throw new ForbiddenException("Consent belongs to another tenant");
        if (Instant.now().isAfter(consent.getExpiresAt())) {
            consent.setStatus(ConsentStatus.EXPIRED);
            throw new ConsentException("Consent has expired");
        }
        if (!ConsentStatus.AUTHORISED.equals(consent.getStatus())) {
            throw new ConsentException("Consent is not authorised (status=" + consent.getStatus() + ")");
        }
        var req = new ExternalTransferRequest(tenantId, consent.getUserId(), consent.getSourceAccountId(),
            consent.getBeneficiaryAccountId(), consent.getAmount(), consent.getCurrency(), "ob-payment",
            consentReference + ":submit", "web", "GB", "GB", "SANDBOX", scenario);
        ExternalPaymentResponse response = externalPayments.initiate(req, FraudContext.lowRisk(),
            Money.of("100000.00", consent.getCurrency()));
        consent.setTransactionId(response.transactionId());
        consent.setStatus(ConsentStatus.SUBMITTED);
        return response;
    }
}
