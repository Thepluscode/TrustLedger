package com.trustledger.rails.paystack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentRailAdapter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class PaystackWebhookOtpTest {

    private static final String SECRET = "sk_test_webhook-secret";

    @Test
    void parsesNativeTransferSuccessAndVerifiesRawBodySignatureEvenAfterProviderDisable() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        TenantProviderConfigRepository configs = mock(TenantProviderConfigRepository.class);
        TenantProviderConfigEntity config = config(tenant, configId, false);
        when(configs.findByIdAndTenantId(configId, tenant)).thenReturn(Optional.of(config));
        PaystackPaymentRailAdapter adapter = new PaystackPaymentRailAdapter(configs, ref -> SECRET,
            mock(PaystackApiClient.class), new ObjectMapper());
        String body = "{\"event\":\"transfer.success\",\"data\":{"
            + "\"id\":4421,\"reference\":\"paystack_1234567890\"," 
            + "\"transfer_code\":\"TRF_native\"}}";

        PaymentRailAdapter.ProviderWebhookEvent event = adapter.parseWebhook(body);

        assertNotNull(event);
        assertEquals("transfer.success:4421", event.eventId());
        assertEquals("paystack_1234567890", event.providerReference());
        assertEquals(ExternalPaymentStatus.SETTLED, event.eventType());
        assertEquals("TRF_native", event.providerObjectId());
        assertTrue(adapter.verifyWebhook(new PaymentRailAdapter.WebhookVerificationRequest(tenant, configId,
            "SANDBOX", body, signature(body, SECRET))));
        assertFalse(adapter.verifyWebhook(new PaymentRailAdapter.WebhookVerificationRequest(tenant, configId,
            "SANDBOX", body, "deadbeef")));
    }

    @Test
    void normalizesFailedReversedAndUnknownTransferEvents() {
        PaystackPaymentRailAdapter adapter = new PaystackPaymentRailAdapter(
            mock(TenantProviderConfigRepository.class), ref -> SECRET, mock(PaystackApiClient.class),
            new ObjectMapper());

        assertEquals(ExternalPaymentStatus.FAILED,
            adapter.parseWebhook(body("transfer.failed", 1)).eventType());
        assertEquals(ExternalPaymentStatus.REVERSED,
            adapter.parseWebhook(body("transfer.reversed", 2)).eventType());
        assertEquals("IGNORED", adapter.parseWebhook(body("charge.success", 3)).eventType());
    }

    @Test
    void otpActionPassesSensitiveValueToClientButReturnsOnlyCanonicalState() {
        UUID tenant = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        TenantProviderConfigRepository configs = mock(TenantProviderConfigRepository.class);
        when(configs.findByIdAndTenantId(configId, tenant)).thenReturn(Optional.of(config(tenant, configId, true)));
        PaystackApiClient api = mock(PaystackApiClient.class);
        when(api.finalizeTransfer(eq(SECRET), any())).thenReturn(new PaystackApiClient.PaystackResponse(
            "pending", "paystack_1234567890", "TRF_native", "accepted", 200, false));
        PaystackPaymentRailAdapter adapter = new PaystackPaymentRailAdapter(configs, ref -> SECRET, api,
            new ObjectMapper());

        PaymentRailAdapter.PaymentSubmitResult result = adapter.executeAction(
            new PaymentRailAdapter.PaymentActionRequest(tenant, UUID.randomUUID(), configId, "SANDBOX",
                "paystack_1234567890", "TRF_native", PaystackPaymentRailAdapter.OTP_FINALIZE, "123456"));

        assertEquals(ExternalPaymentStatus.PENDING_SETTLEMENT, result.status());
        assertEquals("TRF_native", result.providerObjectId());
        ArgumentCaptor<PaystackApiClient.FinalizeTransferRequest> request =
            ArgumentCaptor.forClass(PaystackApiClient.FinalizeTransferRequest.class);
        verify(api).finalizeTransfer(eq(SECRET), request.capture());
        assertEquals("TRF_native", request.getValue().transferCode());
        assertEquals("123456", request.getValue().otp());
    }

    private static TenantProviderConfigEntity config(UUID tenant, UUID id, boolean enabled) {
        return new TenantProviderConfigEntity(id, tenant, PaystackPaymentRailAdapter.RAIL, "SANDBOX", enabled,
            "APPROVED", null, null, "env://PAYSTACK_TEST_KEY", "env://PAYSTACK_WEBHOOK_KEY",
            "NGN", "NG", BigDecimal.ONE, null);
    }

    private static String body(String event, int id) {
        return "{\"event\":\"" + event + "\",\"data\":{\"id\":" + id
            + ",\"reference\":\"paystack_1234567890\",\"transfer_code\":\"TRF_native\"}}";
    }

    private static String signature(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        StringBuilder out = new StringBuilder();
        for (byte b : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) out.append(String.format("%02x", b));
        return out.toString();
    }
}
