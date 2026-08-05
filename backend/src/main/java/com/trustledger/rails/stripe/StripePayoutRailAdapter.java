package com.trustledger.rails.stripe;

import com.trustledger.core.model.Money;
import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentProviderCapabilities;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.secrets.ProviderCredentialResolver;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stripe payout adapter — and, as much as a feature, a test of whether {@link PaymentRailAdapter}
 * actually generalises.
 *
 * <p>Until now there was exactly one real provider (Paystack) plus a sandbox stub, so the claim
 * "new providers can be added without rewriting payment logic" had never been exercised. Stripe was
 * chosen precisely because it is structurally <b>unlike</b> Paystack in the three places an
 * abstraction usually leaks:
 *
 * <ul>
 *   <li><b>Webhook signatures.</b> Paystack signs the raw body with HMAC-SHA512. Stripe signs
 *       {@code timestamp + "." + body} with HMAC-SHA256 and publishes the timestamp in the header,
 *       so verification must also enforce a <b>tolerance window</b> — without it a captured webhook
 *       replays forever. The interface accommodated this only because it hands adapters the raw body
 *       and the raw signature header and lets each decide what a signature means.</li>
 *   <li><b>Currency.</b> Paystack here is NGN-only with a hardcoded 2-decimal conversion. Stripe is
 *       multi-currency including zero-decimal ones, so amounts must go through
 *       {@link Money#toMinorUnits()} — ¥1000 is 1000, not 100000. This adapter is the first thing in
 *       the system to actually depend on that.</li>
 *   <li><b>Status vocabulary.</b> Different words for the same lifecycle, including
 *       {@code in_transit} which has no Paystack equivalent and must not collapse into SETTLED.</li>
 * </ul>
 *
 * <p><b>Honest scope: this is not a certified Stripe integration.</b> {@link StripePayoutApiClient}
 * has no HTTP implementation in this change — signature verification, status normalisation,
 * minor-unit handling and capability declaration are complete and tested; the transport needs a
 * Stripe test account to certify, and shipping an unverified HTTP client would be pretending
 * otherwise. Routing will not select this provider until a tenant configuration exists for it and it
 * passes certification.
 */
@Component
public class StripePayoutRailAdapter implements PaymentRailAdapter {

    public static final String RAIL = "STRIPE";

    private static final com.fasterxml.jackson.databind.ObjectMapper ENVELOPE_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /** Stripe's own recommended replay window. A signature older than this is refused. */
    private static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private final TenantProviderConfigRepository configs;
    private final ProviderCredentialResolver credentials;
    private final StripePayoutApiClient api;
    private final Duration tolerance;

    @Autowired
    public StripePayoutRailAdapter(TenantProviderConfigRepository configs,
                                   ProviderCredentialResolver credentials,
                                   StripePayoutApiClient api,
                                   @Value("${trustledger.stripe.webhook-tolerance-seconds:300}") long toleranceSeconds) {
        this(configs, credentials, api, Duration.ofSeconds(toleranceSeconds));
    }

    public StripePayoutRailAdapter(TenantProviderConfigRepository configs,
                                   ProviderCredentialResolver credentials,
                                   StripePayoutApiClient api, Duration tolerance) {
        this.configs = configs;
        this.credentials = credentials;
        this.api = api;
        this.tolerance = tolerance == null ? DEFAULT_TOLERANCE : tolerance;
    }

    @Override public String rail() { return RAIL; }
    @Override public Set<String> aliases() { return Set.of(RAIL, "stripe"); }

    /**
     * Multi-currency and multi-country, unlike the NGN/NG-only Paystack adapter. Empty sets mean
     * "no restriction declared here" — tenant provider configuration still constrains what is
     * actually allowed, so a broad capability never widens a tenant's policy.
     */
    @Override
    public PaymentProviderCapabilities capabilities() {
        return new PaymentProviderCapabilities(
            Set.of("USD", "EUR", "GBP", "JPY", "AUD", "CAD"),
            Set.of("US", "GB", "IE", "DE", "FR", "NL", "AU", "CA", "JP"),
            BigDecimal.ONE, null, 30);
    }

    @Override
    public PaymentSubmitResult initiatePayment(PaymentSubmitRequest request) {
        requireReference(request.providerReference());
        if (request.providerRecipientCode() == null || request.providerRecipientCode().isBlank()) {
            throw new IllegalArgumentException("Stripe payout requires a destination account");
        }
        TenantProviderConfigEntity config = requireExecutableConfig(request.tenantId(),
            request.tenantProviderConfigId(), request.providerEnvironment());

        // Minor units via Money, which knows each currency's scale. A hardcoded ×100 would silently
        // multiply a JPY payout by 100 and divide a KWD payout by 10.
        long amountMinor = Money.of(request.amount().toPlainString(), request.currency()).toMinorUnits();

        try {
            StripePayoutApiClient.StripeResponse response = api.createPayout(activeSecret(config),
                new StripePayoutApiClient.CreatePayoutRequest(amountMinor,
                    request.currency().toLowerCase(Locale.ROOT), request.providerRecipientCode(),
                    request.providerReference(), "TrustLedger payout " + request.transactionId()));
            String status = response.definitiveFailure()
                ? ExternalPaymentStatus.FAILED : normalize(response.status());
            return new PaymentSubmitResult(request.providerReference(), status, response.id());
        } catch (StripePayoutApiClient.AmbiguousStripeException e) {
            // No authoritative outcome. Park, reconcile, never infer failure — invariant 10.
            throw new PaymentRailTimeoutException(request.providerReference(),
                "Stripe did not return an authoritative payout outcome");
        }
    }

    @Override
    public String getPaymentStatus(String providerReference) {
        return ExternalPaymentStatus.PENDING_UNKNOWN;
    }

    @Override
    public String getPaymentStatus(PaymentStatusRequest request) {
        requireReference(request.providerReference());
        TenantProviderConfigEntity config = requireConfigIdentity(request.tenantId(),
            request.tenantProviderConfigId(), request.providerEnvironment());
        try {
            return normalize(api.retrievePayout(activeSecret(config), request.providerReference()).status());
        } catch (StripePayoutApiClient.AmbiguousStripeException e) {
            return ExternalPaymentStatus.PENDING_UNKNOWN;
        }
    }

    /**
     * Stripe's scheme: {@code Stripe-Signature: t=<unix>,v1=<hex hmac-sha256 of "t.body">}.
     *
     * <p>Three properties this must hold, each of which has been a real CVE class somewhere:
     * constant-time comparison, the timestamp being <b>inside</b> the signed payload (so it cannot be
     * edited to defeat the window), and a tolerance window that makes a captured webhook stop working.
     * Multiple {@code v1} values are accepted because that is how Stripe rolls a signing secret.
     */
    @Override
    public boolean verifyWebhook(WebhookVerificationRequest request) {
        if (request.signature() == null || request.rawBody() == null) return false;
        try {
            TenantProviderConfigEntity config = requireConfigIdentity(request.tenantId(),
                request.tenantProviderConfigId(), request.providerEnvironment());

            Long timestamp = null;
            boolean anySignature = false;
            for (String part : request.signature().split(",")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length != 2) continue;
                if ("t".equals(kv[0])) timestamp = parseLong(kv[1]);
                else if ("v1".equals(kv[0])) anySignature = true;
            }
            if (timestamp == null || !anySignature) return false;

            // Replay window. Rejected on both sides: a far-future timestamp is as suspicious as an old
            // one, and accepting it would let an attacker mint a signature that stays valid.
            long skew = Math.abs(Instant.now().getEpochSecond() - timestamp);
            if (skew > tolerance.getSeconds()) return false;

            String signedPayload = timestamp + "." + request.rawBody();
            // WEBHOOK, not API: Stripe signs webhooks with a separate `whsec_` signing secret,
            // whereas Paystack signs with its API key. The credential model already separates the two
            // purposes; using API here would work for Paystack and silently fail for Stripe.
            for (ProviderCredentialResolver.ResolvedCredential candidate :
                    credentials.verificationCandidates(config, ProviderCredentialResolver.WEBHOOK)) {
                String secret = candidate.secretValue();
                if (secret == null) continue;
                String expected = hmacSha256Hex(secret, signedPayload);
                for (String part : request.signature().split(",")) {
                    String[] kv = part.trim().split("=", 2);
                    if (kv.length == 2 && "v1".equals(kv[0])
                        && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                                                 kv[1].trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parses Stripe's event envelope: {@code {"id":"evt_…","type":"payout.paid","data":{"object":{…}}}}.
     *
     * <p>Without this the adapter could verify a signature on an event it could not read — signature
     * verification and envelope parsing are separate halves of the same path, and shipping one without
     * the other is an adapter that looks finished and drops every webhook.
     *
     * <p>The payout's own {@code id} is the provider reference we submitted against, and Stripe's
     * event {@code id} is the dedup key. An unrecognised event type maps to IGNORED rather than being
     * dropped as null, so it is still recorded by the inbox instead of vanishing.
     */
    @Override
    public ProviderWebhookEvent parseWebhook(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode root = ENVELOPE_MAPPER.readTree(rawBody);
            String eventId = text(root, "id");
            String type = text(root, "type");
            com.fasterxml.jackson.databind.JsonNode object = root.path("data").path("object");
            String payoutId = text(object, "id");
            if (eventId == null || type == null || payoutId == null) return null;

            String canonical = switch (type) {
                case "payout.paid" -> ExternalPaymentStatus.SETTLED;
                case "payout.failed" -> ExternalPaymentStatus.FAILED;
                case "payout.canceled" -> ExternalPaymentStatus.CANCELLED;
                // Created/updated carry no terminal meaning; recorded, dispatched nowhere.
                default -> "IGNORED";
            };
            return new ProviderWebhookEvent(eventId, payoutId, canonical, payoutId);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    /**
     * Stripe payout lifecycle. {@code in_transit} deliberately does NOT collapse into SETTLED: the
     * money has left but has not arrived, and treating it as settled would post the ledger ahead of
     * reality. An unrecognised status is PENDING_UNKNOWN, never a guess.
     */
    static String normalize(String stripeStatus) {
        if (stripeStatus == null || stripeStatus.isBlank()) return ExternalPaymentStatus.PENDING_UNKNOWN;
        return switch (stripeStatus.trim().toLowerCase(Locale.ROOT)) {
            case "paid" -> ExternalPaymentStatus.SETTLED;
            case "pending", "in_transit" -> ExternalPaymentStatus.PENDING_SETTLEMENT;
            case "failed" -> ExternalPaymentStatus.FAILED;
            case "canceled", "cancelled" -> ExternalPaymentStatus.CANCELLED;
            case "reversed" -> ExternalPaymentStatus.REVERSED;
            default -> ExternalPaymentStatus.PENDING_UNKNOWN;
        };
    }

    static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Stripe signature computation failed", e);
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void requireReference(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Stripe payout requires a provider reference");
        }
    }

    private TenantProviderConfigEntity requireExecutableConfig(UUID tenantId, UUID configId, String environment) {
        TenantProviderConfigEntity config = requireConfigIdentity(tenantId, configId, environment);
        if (!config.isEnabled() || config.isEmergencyDisabled()
            || !"APPROVED".equals(config.getComplianceStatus())
            || !"ACTIVE".equals(config.getOperationalStatus())) {
            throw new IllegalStateException("Stripe provider configuration is not executable");
        }
        return config;
    }

    private TenantProviderConfigEntity requireConfigIdentity(UUID tenantId, UUID configId, String environment) {
        if (configId == null) throw new IllegalArgumentException("Stripe provider configuration is required");
        TenantProviderConfigEntity config = configs.findByIdAndTenantId(configId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Stripe provider configuration not found"));
        if (!RAIL.equalsIgnoreCase(config.getProvider())) {
            throw new IllegalArgumentException("Provider configuration is not Stripe");
        }
        if (environment == null || !environment.equalsIgnoreCase(config.getEnvironment())) {
            throw new IllegalArgumentException("Stripe provider environment mismatch");
        }
        return config;
    }

    private String activeSecret(TenantProviderConfigEntity config) {
        String secret = credentials.active(config, ProviderCredentialResolver.API).secretValue();
        boolean valid = secret != null && ("PRODUCTION".equalsIgnoreCase(config.getEnvironment())
            ? secret.startsWith("sk_live_") : secret.startsWith("sk_test_"));
        if (!valid) throw new IllegalStateException("Stripe credential does not match configured environment");
        return secret;
    }
}
