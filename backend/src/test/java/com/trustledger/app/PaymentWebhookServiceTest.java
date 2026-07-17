package com.trustledger.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.trustledger.persistence.entity.ExternalPaymentAttemptEntity;
import com.trustledger.persistence.entity.PaymentWebhookEventEntity;
import com.trustledger.persistence.repo.ExternalPaymentAttemptRepository;
import com.trustledger.persistence.repo.PaymentWebhookEventRepository;
import com.trustledger.rails.ExternalPaymentStatus;
import com.trustledger.rails.PaymentRailAdapter;
import com.trustledger.rails.PaymentRailRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PaymentWebhookServiceTest {

    @Test
    void verifiesWithExactAttemptContextAndSettlesOnce() {
        Fixture fixture = fixture(ExternalPaymentStatus.SETTLED, true);

        assertEquals(PaymentWebhookService.Result.PROCESSED,
            fixture.service().process("NATIVE", "{}", "valid"));

        verify(fixture.transitions()).settle(fixture.attemptId());
        assertEquals(fixture.tenantId(), fixture.adapter().verification.get().tenantId());
        assertEquals(fixture.configId(), fixture.adapter().verification.get().tenantProviderConfigId());
        assertEquals("SANDBOX", fixture.adapter().verification.get().providerEnvironment());
        verify(fixture.webhookEvents(), times(2)).save(any(PaymentWebhookEventEntity.class));
    }

    @Test
    void invalidSignatureRecordsEvidenceButNeverMutatesMoney() {
        Fixture fixture = fixture(ExternalPaymentStatus.SETTLED, false);

        assertEquals(PaymentWebhookService.Result.INVALID_SIGNATURE,
            fixture.service().process("NATIVE", "{}", "invalid"));

        verifyNoInteractions(fixture.transitions());
        verify(fixture.webhookEvents()).save(argThat(event -> !event.isSignatureValid()));
    }

    @Test
    void reversedEventUsesCompensatingAccountingAndDuplicateIsIgnored() {
        Fixture fixture = fixture(ExternalPaymentStatus.REVERSED, true);
        when(fixture.webhookEvents().findByProviderAndEventId("NATIVE", "evt-1"))
            .thenReturn(Optional.of(mock(PaymentWebhookEventEntity.class)));

        assertEquals(PaymentWebhookService.Result.DUPLICATE,
            fixture.service().process("NATIVE", "{}", "valid"));
        verifyNoInteractions(fixture.transitions());

        reset(fixture.webhookEvents());
        when(fixture.webhookEvents().findByProviderAndEventId("NATIVE", "evt-1")).thenReturn(Optional.empty());
        when(fixture.webhookEvents().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertEquals(PaymentWebhookService.Result.PROCESSED,
            fixture.service().process("NATIVE", "{}", "valid"));
        verify(fixture.transitions()).reverse(fixture.attemptId());
    }

    @Test
    void providerObjectMismatchDoesNotConsumeLegitimateEventId() {
        Fixture fixture = fixture(ExternalPaymentStatus.SETTLED, true, "TRF_other");
        when(fixture.transitions().bindProviderObjectId(fixture.attemptId(), "TRF_other"))
            .thenReturn(false);

        assertEquals(PaymentWebhookService.Result.BAD_REQUEST,
            fixture.service().process("NATIVE", "{}", "valid"));

        verify(fixture.transitions()).bindProviderObjectId(fixture.attemptId(), "TRF_other");
        verify(fixture.transitions(), never()).settle(any());
        verify(fixture.webhookEvents(), never()).save(argThat(event -> "evt-1".equals(event.getEventId())));
        verify(fixture.webhookEvents()).save(argThat(event -> event.getEventId().startsWith("conflict:")));
    }

    private static Fixture fixture(String eventType, boolean signatureValid) {
        return fixture(eventType, signatureValid, null);
    }

    private static Fixture fixture(String eventType, boolean signatureValid, String providerObjectId) {
        UUID tenant = UUID.randomUUID();
        UUID config = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        ExternalPaymentAttemptEntity attempt = mock(ExternalPaymentAttemptEntity.class);
        when(attempt.getId()).thenReturn(attemptId);
        when(attempt.getTenantId()).thenReturn(tenant);
        when(attempt.getTenantProviderConfigId()).thenReturn(config);
        when(attempt.getProviderEnvironment()).thenReturn("SANDBOX");

        NativeAdapter adapter = new NativeAdapter(eventType, signatureValid, providerObjectId);
        PaymentWebhookEventRepository webhookEvents = mock(PaymentWebhookEventRepository.class);
        when(webhookEvents.findByProviderAndEventId("NATIVE", "evt-1")).thenReturn(Optional.empty());
        when(webhookEvents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ExternalPaymentAttemptRepository attempts = mock(ExternalPaymentAttemptRepository.class);
        when(attempts.findByProviderAndProviderReference("NATIVE", "ref-1")).thenReturn(Optional.of(attempt));
        ExternalPaymentTransitionService transitions = mock(ExternalPaymentTransitionService.class);
        when(transitions.bindProviderObjectId(any(), anyString())).thenReturn(true);
        PaymentWebhookService service = new PaymentWebhookService(webhookEvents, attempts, transitions,
            new PaymentRailRegistry(List.of(adapter)), new ObjectMapper());
        return new Fixture(service, adapter, webhookEvents, transitions, attemptId, tenant, config);
    }

    private record Fixture(PaymentWebhookService service, NativeAdapter adapter,
                           PaymentWebhookEventRepository webhookEvents,
                           ExternalPaymentTransitionService transitions,
                           UUID attemptId, UUID tenantId, UUID configId) {}

    private static final class NativeAdapter implements PaymentRailAdapter {
        private final String eventType;
        private final boolean signatureValid;
        private final String providerObjectId;
        private final AtomicReference<WebhookVerificationRequest> verification = new AtomicReference<>();

        private NativeAdapter(String eventType, boolean signatureValid, String providerObjectId) {
            this.eventType = eventType;
            this.signatureValid = signatureValid;
            this.providerObjectId = providerObjectId;
        }

        @Override public String rail() { return "NATIVE"; }
        @Override public Set<String> aliases() { return Set.of("NATIVE"); }
        @Override public ProviderWebhookEvent parseWebhook(String rawBody) {
            return new ProviderWebhookEvent("evt-1", "ref-1", eventType, providerObjectId);
        }
        @Override public boolean verifyWebhook(WebhookVerificationRequest request) {
            verification.set(request);
            return signatureValid;
        }
        @Override public PaymentSubmitResult initiatePayment(PaymentSubmitRequest request) {
            return new PaymentSubmitResult(request.providerReference(), ExternalPaymentStatus.PENDING_SETTLEMENT);
        }
        @Override public String getPaymentStatus(String providerReference) {
            return ExternalPaymentStatus.PENDING_UNKNOWN;
        }
    }
}
