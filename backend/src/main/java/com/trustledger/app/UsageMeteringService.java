package com.trustledger.app;

import com.trustledger.persistence.entity.UsageRecordEntity;
import com.trustledger.persistence.repo.UsageRecordRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records and sums per-tenant usage for billing, quotas, and capacity planning. */
@Service
public class UsageMeteringService {

    public static final String TRANSFERS_CREATED = "transfers_created";
    public static final String EVIDENCE_EXPORTS = "evidence_exports_generated";

    private final UsageRecordRepository usage;

    public UsageMeteringService(UsageRecordRepository usage) {
        this.usage = usage;
    }

    private static LocalDate currentPeriod() { return LocalDate.now().withDayOfMonth(1); }

    @Transactional
    public void record(UUID tenantId, String metric, long quantity) {
        usage.save(new UsageRecordEntity(UUID.randomUUID(), tenantId, metric, quantity, currentPeriod()));
    }

    @Transactional(readOnly = true)
    public long currentMonth(UUID tenantId, String metric) {
        return usage.sumFor(tenantId, metric, currentPeriod());
    }
}
