package com.trustledger.rails.paystack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.secrets.ProviderCredentialResolver;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class PaystackCredentialRotationTest {

    @Test
    void outboundUsesOnlyActiveButWebhookAcceptsActiveAndGrace() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        TenantProviderConfigEntity config = new TenantProviderConfigEntity(configId, tenant, "PAYSTACK",
            "SANDBOX", true, "APPROVED", null, null, "vault://api", "vault://webhook",
            "NGN", "NG", BigDecimal.ONE, null);
        TenantProviderConfigRepository configs = mock(TenantProviderConfigRepository.class);
        when(configs.findByIdAndTenantId(configId, tenant)).thenReturn(Optional.of(config));
        ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
        when(credentials.active(config, ProviderCredentialResolver.API))
            .thenReturn(new ProviderCredentialResolver.ResolvedCredential(UUID.randomUUID(), 2, "sk_test_new"));
        when(credentials.verificationCandidates(config, ProviderCredentialResolver.API)).thenReturn(List.of(
            new ProviderCredentialResolver.ResolvedCredential(UUID.randomUUID(), 2, "sk_test_new"),
            new ProviderCredentialResolver.ResolvedCredential(UUID.randomUUID(), 1, "sk_test_old")));
        PaystackApiClient api = mock(PaystackApiClient.class);
        when(api.initiateTransfer(anyString(), any())).thenReturn(new PaystackApiClient.PaystackResponse(
            "pending", "paystack_rotation_1234", "TRF_rotation", "queued", 200, false));
        PaystackPaymentRailAdapter adapter = new PaystackPaymentRailAdapter(configs, credentials, api,
            new ObjectMapper());

        PaymentRailAdapter.PaymentSubmitResult submitted = adapter.initiatePayment(
            new PaymentRailAdapter.PaymentSubmitRequest(tenant, UUID.randomUUID(), "paystack_rotation_1234",
                configId, "SANDBOX", UUID.randomUUID(), UUID.randomUUID(), "RCP_rotation",
                new BigDecimal("100.00"), "NGN", "success"));

        assertEquals(ExternalPaymentStatus.PENDING_SETTLEMENT, submitted.status());
        ArgumentCaptor<String> executionSecret = ArgumentCaptor.forClass(String.class);
        verify(api).initiateTransfer(executionSecret.capture(), any());
        assertEquals("sk_test_new", executionSecret.getValue());

        String body = "{\"event\":\"transfer.success\",\"data\":{\"id\":1,"
            + "\"reference\":\"paystack_rotation_1234\"}}";
        assertTrue(adapter.verifyWebhook(new PaymentRailAdapter.WebhookVerificationRequest(tenant, configId,
            "SANDBOX", body, signature(body, "sk_test_old"))));
        assertTrue(adapter.verifyWebhook(new PaymentRailAdapter.WebhookVerificationRequest(tenant, configId,
            "SANDBOX", body, signature(body, "sk_test_new"))));

        when(credentials.verificationCandidates(config, ProviderCredentialResolver.API)).thenReturn(List.of(
            new ProviderCredentialResolver.ResolvedCredential(UUID.randomUUID(), 2, "sk_test_new")));
        assertFalse(adapter.verifyWebhook(new PaymentRailAdapter.WebhookVerificationRequest(tenant, configId,
            "SANDBOX", body, signature(body, "sk_test_old"))));
    }

    private static String signature(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        StringBuilder out = new StringBuilder();
        for (byte b : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) out.append(String.format("%02x", b));
        return out.toString();
    }
}
