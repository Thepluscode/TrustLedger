package com.trustledger.rails.paystack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.trustledger.persistence.entity.TenantProviderConfigEntity;
import com.trustledger.persistence.repo.TenantProviderConfigRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.secrets.SecretResolver;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaystackPaymentRailAdapterTest {

    @Test
    void convertsNgnToMinorUnitsExactly() {
        assertEquals(100025L, PaystackPaymentRailAdapter.minorUnits(new BigDecimal("1000.25")));
        assertThrows(IllegalArgumentException.class,
            () -> PaystackPaymentRailAdapter.minorUnits(new BigDecimal("1.001")));
        assertThrows(IllegalArgumentException.class,
            () -> PaystackPaymentRailAdapter.minorUnits(BigDecimal.ZERO));
    }

    @Test
    void normalizesPaystackLifecycleWithoutCollapsingOtp() {
        assertEquals(ExternalPaymentStatus.SETTLED, PaystackPaymentRailAdapter.normalize("success"));
        assertEquals(ExternalPaymentStatus.PENDING_SETTLEMENT, PaystackPaymentRailAdapter.normalize("pending"));
        assertEquals(ExternalPaymentStatus.ACTION_REQUIRED, PaystackPaymentRailAdapter.normalize("otp"));
        assertEquals(ExternalPaymentStatus.REVERSED, PaystackPaymentRailAdapter.normalize("reversed"));
        assertEquals(ExternalPaymentStatus.PENDING_UNKNOWN, PaystackPaymentRailAdapter.normalize("new-state"));
    }

    @Test
    void sendsExactRecipientAndDefersImmediateSuccessToVerification() {
        Fixture fixture = fixture(new PaystackApiClient.PaystackResponse("success", "paystack_1234567890",
            "TRF_test", "ok", 200, false));

        PaymentRailAdapter.PaymentSubmitResult result = fixture.adapter().initiatePayment(request());

        assertEquals(ExternalPaymentStatus.PENDING_SETTLEMENT, result.status());
        assertEquals(100025L, fixture.api().lastRequest.amountMinor());
        assertEquals("RCP_123456789", fixture.api().lastRequest.recipientCode());
        assertEquals("paystack_1234567890", fixture.api().lastRequest.reference());
        assertEquals("NGN", fixture.api().lastRequest.currency());
        assertEquals("sk_test_not-real", fixture.api().lastSecret);
    }

    @Test
    void preservesOtpAsActionRequired() {
        Fixture fixture = fixture(new PaystackApiClient.PaystackResponse("otp", "paystack_1234567890",
            "TRF_test", "otp required", 200, false));
        assertEquals(ExternalPaymentStatus.ACTION_REQUIRED, fixture.adapter().initiatePayment(request()).status());
    }

    @Test
    void ambiguousTransportBecomesPendingUnknownNotFailure() {
        RecordingClient api = new RecordingClient(null);
        api.throwAmbiguous = true;
        Fixture fixture = fixture(api);
        assertThrows(PaymentRailAdapter.PaymentRailTimeoutException.class,
            () -> fixture.adapter().initiatePayment(request()));
    }

    @Test
    void verificationUsesExactTenantConfigurationAndCanSettle() {
        RecordingClient api = new RecordingClient(new PaystackApiClient.PaystackResponse("success",
            "paystack_1234567890", "TRF_test", "ok", 200, false));
        Fixture fixture = fixture(api);
        String status = fixture.adapter().getPaymentStatus(new PaymentRailAdapter.PaymentStatusRequest(
            fixture.tenantId(), fixture.configId(), "SANDBOX", "paystack_1234567890"));
        assertEquals(ExternalPaymentStatus.SETTLED, status);
        assertEquals("sk_test_not-real", api.lastSecret);
    }

    @Test
    void rejectsMissingRecipientAndEnvironmentKeyMismatchBeforeNetwork() {
        Fixture fixture = fixture(new PaystackApiClient.PaystackResponse("pending", null, null, "ok", 200, false));
        var request = new PaymentRailAdapter.PaymentSubmitRequest(fixture.tenantId(), UUID.randomUUID(),
            "paystack_1234567890", fixture.configId(), "SANDBOX", null, null, null,
            new BigDecimal("1000.25"), "NGN", null);
        assertThrows(IllegalArgumentException.class, () -> fixture.adapter().initiatePayment(request));

        TenantProviderConfigRepository repo = mock(TenantProviderConfigRepository.class);
        UUID tenant = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        when(repo.findByIdAndTenantId(configId, tenant)).thenReturn(Optional.of(config(tenant, configId)));
        PaystackPaymentRailAdapter mismatch = new PaystackPaymentRailAdapter(repo,
            ref -> "sk_live_wrong-environment", new RecordingClient(null));
        assertThrows(IllegalStateException.class, () -> mismatch.initiatePayment(request(tenant, configId)));
    }

    private static Fixture fixture(PaystackApiClient.PaystackResponse response) {
        return fixture(new RecordingClient(response));
    }

    private static Fixture fixture(RecordingClient api) {
        UUID tenant = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        TenantProviderConfigRepository repo = mock(TenantProviderConfigRepository.class);
        when(repo.findByIdAndTenantId(configId, tenant)).thenReturn(Optional.of(config(tenant, configId)));
        SecretResolver secrets = ref -> "sk_test_not-real";
        return new Fixture(new PaystackPaymentRailAdapter(repo, secrets, api), api, tenant, configId);
    }

    private static TenantProviderConfigEntity config(UUID tenant, UUID id) {
        return new TenantProviderConfigEntity(id, tenant, PaystackPaymentRailAdapter.RAIL, "SANDBOX", true,
            "APPROVED", null, null, "env://PAYSTACK_TEST_KEY", "env://PAYSTACK_WEBHOOK_KEY",
            "NGN", "NG", BigDecimal.ONE, null);
    }

    private static PaymentRailAdapter.PaymentSubmitRequest request() {
        UUID tenant = UUID.randomUUID();
        UUID configId = UUID.randomUUID();
        return request(tenant, configId);
    }

    private static PaymentRailAdapter.PaymentSubmitRequest request(UUID tenant, UUID configId) {
        return new PaymentRailAdapter.PaymentSubmitRequest(tenant, UUID.randomUUID(), "paystack_1234567890",
            configId, "SANDBOX", UUID.randomUUID(), UUID.randomUUID(), "RCP_123456789",
            new BigDecimal("1000.25"), "NGN", null);
    }

    private record Fixture(PaystackPaymentRailAdapter adapter, RecordingClient api,
                           UUID tenantId, UUID configId) {
        private PaymentRailAdapter.PaymentSubmitRequest request() { return request(tenantId, configId); }
    }

    private static final class RecordingClient implements PaystackApiClient {
        private final PaystackResponse response;
        private InitiateTransferRequest lastRequest;
        private String lastSecret;
        private boolean throwAmbiguous;

        private RecordingClient(PaystackResponse response) { this.response = response; }

        @Override
        public PaystackResponse initiateTransfer(String secretKey, InitiateTransferRequest request) {
            if (throwAmbiguous) throw new AmbiguousPaystackException("ambiguous");
            this.lastSecret = secretKey;
            this.lastRequest = request;
            return response;
        }

        @Override
        public PaystackResponse verifyTransfer(String secretKey, String reference) {
            if (throwAmbiguous) throw new AmbiguousPaystackException("ambiguous");
            this.lastSecret = secretKey;
            return response;
        }
    }
}