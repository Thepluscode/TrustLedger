package com.trustledger.app;

import com.trustledger.core.fraud.FraudDecision;
import com.trustledger.core.idempotency.IdempotencyService;
import com.trustledger.persistence.entity.IdempotencyKeyEntity;
import com.trustledger.persistence.repo.*;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Primary external-payment service that short-circuits completed idempotent replays before routing,
 * reserving money, or calling a provider. The base service remains responsible for first execution.
 */
@Service
@Primary
public class ReplaySafeExternalPaymentService extends ExternalPaymentService {

    private final IdempotencyKeyRepository idempotencyKeys;
    private final ObjectMapper json;

    public ReplaySafeExternalPaymentService(AccountRepository accounts, TransferRepository transfers,
                                            ExternalPaymentAttemptRepository attempts,
                                            IdempotencyKeyRepository idempotencyKeys,
                                            LedgerTransactionRepository ledgerTransactions,
                                            LedgerEntryRepository ledgerEntries,
                                            OutboxEventRepository outbox,
                                            AuditLogRepository auditLogs,
                                            FraudCaseRepository fraudCases,
                                            com.trustledger.core.fraud.FraudEngine fraudEngine,
                                            TenantPaymentRouteService routes,
                                            ProviderRecipientResolver recipientResolver,
                                            ExternalRailSubmissionService submissions,
                                            ObjectMapper json) {
        super(accounts, transfers, attempts, idempotencyKeys, ledgerTransactions, ledgerEntries, outbox,
            auditLogs, fraudCases, fraudEngine, routes, recipientResolver, submissions, json);
        this.idempotencyKeys = idempotencyKeys;
        this.json = json;
    }

    @Override
    @Transactional
    public ExternalPaymentResponse initiate(ExternalTransferRequest request, FraudDecision decision) {
        ExternalPaymentResponse replay = completedReplay(request);
        if (replay != null) return replay;
        try {
            return super.initiate(request, decision);
        } catch (RuntimeException executionFailure) {
            // A concurrent request may have completed between the pre-check and the base execution.
            replay = completedReplay(request);
            if (replay != null) return replay;
            throw executionFailure;
        }
    }

    private ExternalPaymentResponse completedReplay(ExternalTransferRequest request) {
        Optional<IdempotencyKeyEntity> existing = idempotencyKeys
            .findByTenantIdAndUserIdAndIdempotencyKey(request.tenantId(), request.userId(), request.idempotencyKey());
        if (existing.isEmpty()) return null;
        IdempotencyKeyEntity record = existing.get();
        if (!record.getRequestHash().equals(fingerprint(request))) {
            throw new IdempotencyConflictException("Idempotency key reused with different payload");
        }
        if ("COMPLETED".equals(record.getStatus()) && record.getResponseBody() != null) {
            try { return json.readValue(record.getResponseBody(), ExternalPaymentResponse.class); }
            catch (Exception e) { throw new IllegalStateException("Stored idempotency response is unreadable", e); }
        }
        throw new IdempotencyConflictException("Request with this idempotency key is still processing");
    }

    private static String fingerprint(ExternalTransferRequest request) {
        return IdempotencyService.sha256(String.join(":",
            value(request.tenantId()), value(request.userId()), value(request.sourceAccountId()),
            value(request.beneficiaryId()), value(request.payoutInstrumentId()), value(request.amount()),
            value(request.currency()), value(request.reference()), value(request.deviceId()),
            value(request.currentCountry()), value(request.destinationCountry()),
            value(request.preferredProvider()), value(request.preferredEnvironment()),
            value(request.scenario()), "EXTERNAL"));
    }

    private static String value(Object value) { return value == null ? "" : value.toString(); }
}