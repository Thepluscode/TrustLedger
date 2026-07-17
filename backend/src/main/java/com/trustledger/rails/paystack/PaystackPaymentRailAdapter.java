package com.trustledger.rails.paystack;

import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentProviderCapabilities;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.secrets.SecretResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Paystack NGN bank-payout adapter with native webhook and OTP support. */
@Component
public class PaystackPaymentRailAdapter implements PaymentRailAdapter {

    public static final String RAIL = "PAYSTACK";
    public static final String OTP_FINALIZE = "OTP_FINALIZE";
    private static final Pattern REFERENCE = Pattern.compile("[a-z0-9_-]{16,50}");

    private final TenantProviderConfigRepository configs;
    private final SecretResolver secrets;
    private final PaystackApiClient api;
    private final ObjectMapper json;

    public PaystackPaymentRailAdapter(TenantProviderConfigRepository configs, SecretResolver secrets,
                                      PaystackApiClient api) {
        this(configs, secrets, api, new ObjectMapper());
    }

    @Autowired
    public PaystackPaymentRailAdapter(TenantProviderConfigRepository configs, SecretResolver secrets,
                                      PaystackApiClient api, ObjectMapper json) {
        this.configs = configs;
        this.secrets = secrets;
        this.api = api;
        this.json = json;
    }

    @Override public String rail() { return RAIL; }
    @Override public Set<String> aliases() { return Set.of(RAIL); }

    @Override
    public PaymentProviderCapabilities capabilities() {
        return new PaymentProviderCapabilities(Set.of("NGN"), Set.of("NG"), BigDecimal.ONE, null, 20);
    }

    @Override
    public PaymentSubmitResult initiatePayment(PaymentSubmitRequest request) {
        requireRecipient(request);
        requireReference(request.providerReference());
        requireCurrency(request.currency());
        TenantProviderConfigEntity config = requireExecutableConfig(request.tenantId(),
            request.tenantProviderConfigId(), request.providerEnvironment());
        String secret = resolveAndValidateSecret(config);
        try {
            PaystackApiClient.PaystackResponse response = api.initiateTransfer(secret,
                new PaystackApiClient.InitiateTransferRequest(minorUnits(request.amount()),
                    request.providerRecipientCode(), request.providerReference(),
                    "TrustLedger payout " + request.transactionId(), "NGN"));
            String normalized = response.definitiveFailure() ? ExternalPaymentStatus.FAILED : normalize(response.status());
            if (ExternalPaymentStatus.SETTLED.equals(normalized)) normalized = ExternalPaymentStatus.PENDING_SETTLEMENT;
            return new PaymentSubmitResult(request.providerReference(), normalized, response.transferCode());
        } catch (PaystackApiClient.AmbiguousPaystackException e) {
            throw new PaymentRailTimeoutException(request.providerReference(),
                "Paystack did not return an authoritative transfer outcome");
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
            return normalize(api.verifyTransfer(resolveAndValidateSecret(config), request.providerReference()).status());
        } catch (PaystackApiClient.AmbiguousPaystackException e) {
            return ExternalPaymentStatus.PENDING_UNKNOWN;
        }
    }

    @Override
    public String getProviderObjectId(PaymentStatusRequest request) {
        requireReference(request.providerReference());
        TenantProviderConfigEntity config = requireConfigIdentity(request.tenantId(),
            request.tenantProviderConfigId(), request.providerEnvironment());
        try {
            return api.verifyTransfer(resolveAndValidateSecret(config), request.providerReference()).transferCode();
        } catch (PaystackApiClient.AmbiguousPaystackException e) {
            return null;
        }
    }

    @Override
    public boolean supportsAction(String action) { return OTP_FINALIZE.equals(action); }

    @Override
    public PaymentSubmitResult executeAction(PaymentActionRequest request) {
        if (!OTP_FINALIZE.equals(request.action())) {
            throw new UnsupportedOperationException("Unsupported Paystack action: " + request.action());
        }
        requireReference(request.providerReference());
        if (request.providerObjectId() == null || request.providerObjectId().isBlank()) {
            throw new IllegalArgumentException("Paystack transfer code is required for OTP finalization");
        }
        if (request.sensitiveValue() == null || request.sensitiveValue().isBlank()) {
            throw new IllegalArgumentException("Paystack OTP is required");
        }
        TenantProviderConfigEntity config = requireExecutableConfig(request.tenantId(),
            request.tenantProviderConfigId(), request.providerEnvironment());
        try {
            PaystackApiClient.PaystackResponse response = api.finalizeTransfer(resolveAndValidateSecret(config),
                new PaystackApiClient.FinalizeTransferRequest(request.providerObjectId(), request.sensitiveValue()));
            String normalized = response.definitiveFailure()
                ? ExternalPaymentStatus.ACTION_REQUIRED : normalize(response.status());
            if (ExternalPaymentStatus.SETTLED.equals(normalized)) normalized = ExternalPaymentStatus.PENDING_SETTLEMENT;
            String objectId = response.transferCode() == null ? request.providerObjectId() : response.transferCode();
            return new PaymentSubmitResult(request.providerReference(), normalized, objectId);
        } catch (PaystackApiClient.AmbiguousPaystackException e) {
            throw new PaymentRailTimeoutException(request.providerReference(),
                "Paystack OTP finalization returned no authoritative outcome");
        }
    }

    @Override
    public ProviderWebhookEvent parseWebhook(String rawBody) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = json.readValue(rawBody, Map.class);
            String event = text(body.get("event"));
            Map<String, Object> data = map(body.get("data"));
            String reference = text(data.get("reference"));
            if (event == null || reference == null) return null;
            String canonical = switch (event) {
                case "transfer.success" -> ExternalPaymentStatus.SETTLED;
                case "transfer.failed" -> ExternalPaymentStatus.FAILED;
                case "transfer.reversed" -> ExternalPaymentStatus.REVERSED;
                default -> "IGNORED";
            };
            return new ProviderWebhookEvent(eventId(event, data, rawBody), reference, canonical,
                text(data.get("transfer_code")));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean verifyWebhook(WebhookVerificationRequest request) {
        if (request.signature() == null || request.signature().isBlank()) return false;
        try {
            TenantProviderConfigEntity config = requireConfigIdentity(request.tenantId(),
                request.tenantProviderConfigId(), request.providerEnvironment());
            String secret = resolveAndValidateSecret(config);
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            String expected = hex(mac.doFinal(request.rawBody().getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                request.signature().trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    static long minorUnits(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Paystack amount must be positive");
        try {
            return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Paystack NGN amount must have at most two decimal places");
        }
    }

    static String normalize(String providerStatus) {
        if (providerStatus == null || providerStatus.isBlank()) return ExternalPaymentStatus.PENDING_UNKNOWN;
        return switch (providerStatus.trim().toLowerCase(Locale.ROOT)) {
            case "success" -> ExternalPaymentStatus.SETTLED;
            case "pending", "received", "queued", "processing" -> ExternalPaymentStatus.PENDING_SETTLEMENT;
            case "otp" -> ExternalPaymentStatus.ACTION_REQUIRED;
            case "failed", "blocked", "rejected" -> ExternalPaymentStatus.FAILED;
            case "reversed" -> ExternalPaymentStatus.REVERSED;
            case "abandoned", "cancelled", "canceled" -> ExternalPaymentStatus.CANCELLED;
            default -> ExternalPaymentStatus.PENDING_UNKNOWN;
        };
    }

    private TenantProviderConfigEntity requireExecutableConfig(UUID tenantId, UUID configId, String environment) {
        TenantProviderConfigEntity config = requireConfigIdentity(tenantId, configId, environment);
        if (!config.isEnabled() || config.isEmergencyDisabled()
            || !"APPROVED".equals(config.getComplianceStatus())
            || !"ACTIVE".equals(config.getOperationalStatus())) {
            throw new IllegalStateException("Paystack provider configuration is not executable");
        }
        return config;
    }

    private TenantProviderConfigEntity requireConfigIdentity(UUID tenantId, UUID configId, String environment) {
        if (configId == null) throw new IllegalArgumentException("Paystack provider configuration is required");
        TenantProviderConfigEntity config = configs.findByIdAndTenantId(configId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Paystack provider configuration not found"));
        if (!RAIL.equalsIgnoreCase(config.getProvider())) {
            throw new IllegalArgumentException("Provider configuration is not Paystack");
        }
        if (environment == null || !environment.equalsIgnoreCase(config.getEnvironment())) {
            throw new IllegalArgumentException("Paystack provider environment mismatch");
        }
        return config;
    }

    private String resolveAndValidateSecret(TenantProviderConfigEntity config) {
        String secret = secrets.resolve(config.getCredentialsSecretRef());
        boolean valid = "PRODUCTION".equalsIgnoreCase(config.getEnvironment())
            ? secret.startsWith("sk_live_") : secret.startsWith("sk_test_");
        if (!valid) throw new IllegalStateException("Paystack credential does not match configured environment");
        return secret;
    }

    private static void requireRecipient(PaymentSubmitRequest request) {
        if (request.payoutInstrumentId() == null || request.providerRecipientMappingId() == null
            || request.providerRecipientCode() == null || request.providerRecipientCode().isBlank()) {
            throw new IllegalArgumentException("Paystack requires an exact provider recipient mapping");
        }
    }

    private static void requireReference(String reference) {
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException(
                "Paystack reference must be 16-50 lowercase letters, digits, underscores, or hyphens");
        }
    }

    private static void requireCurrency(String currency) {
        if (!"NGN".equalsIgnoreCase(currency)) throw new IllegalArgumentException("Paystack adapter currently supports NGN only");
    }

    private static String eventId(String event, Map<String, Object> data, String rawBody) {
        String id = text(data.get("id"));
        if (id != null) return event + ":" + id;
        try {
            return event + ":" + hex(MessageDigest.getInstance("SHA-256")
                .digest(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return event + ":" + Integer.toHexString(rawBody.hashCode());
        }
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format("%02x", b));
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) { return value == null ? null : value.toString(); }
}
