package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.persistence.repo.FraudSignalRepository;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** Tenant fraud-signal analytics — the queryable control graph surfaced as insight for fraud ops. */
@RestController
@RequestMapping("/api/v1/fraud/signals")
public class FraudSignalController {

    /** How often a signal type fired for the tenant, and its cumulative score contribution. */
    public record SignalFrequencyView(String signalType, long occurrences, long totalScoreDelta) {}

    private final FraudSignalRepository fraudSignals;
    private final AccessControlService access;

    public FraudSignalController(FraudSignalRepository fraudSignals, AccessControlService access) {
        this.fraudSignals = fraudSignals;
        this.access = access;
    }

    /** Signal types that fired for the caller's tenant, most frequent first. */
    @GetMapping("/summary")
    public List<SignalFrequencyView> summary() {
        access.require(Permission.FRAUD_CASE_VIEW);
        return fraudSignals.summarizeByTenant(CurrentUser.tenantId()).stream()
            .map(f -> new SignalFrequencyView(f.getSignalType(), f.getOccurrences(), f.getTotalScoreDelta()))
            .toList();
    }
}
