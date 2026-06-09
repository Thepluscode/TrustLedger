package com.trustledger.app;

import com.trustledger.persistence.entity.BillingAccountEntity;
import com.trustledger.persistence.entity.BillingEventEntity;
import com.trustledger.persistence.entity.TenantEntity;
import com.trustledger.persistence.repo.BillingAccountRepository;
import com.trustledger.persistence.repo.BillingEventRepository;
import com.trustledger.persistence.repo.TenantRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Billing readiness: emits billing events (for Stripe/Chargebee sync later). Separate from the money ledger. */
@Service
public class BillingService {

    public static final String TENANT_CREATED = "TENANT_CREATED";
    public static final String PLAN_CHANGED = "PLAN_CHANGED";

    private final BillingEventRepository events;
    private final BillingAccountRepository accounts;
    private final TenantRepository tenants;

    public BillingService(BillingEventRepository events, BillingAccountRepository accounts, TenantRepository tenants) {
        this.events = events;
        this.accounts = accounts;
        this.tenants = tenants;
    }

    @Transactional
    public void recordEvent(UUID tenantId, String type, String detail) {
        events.save(new BillingEventEntity(UUID.randomUUID(), tenantId, type, detail));
    }

    @Transactional(readOnly = true)
    public List<BillingEventEntity> events(UUID tenantId) {
        return events.findByTenantId(tenantId);
    }

    /** Change a tenant's plan: updates the tenant + billing account and emits PLAN_CHANGED. */
    @Transactional
    public void changePlan(UUID tenantId, String plan) {
        TenantEntity tenant = tenants.findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        String previous = tenant.getPlan();
        tenant.setPlan(plan);
        BillingAccountEntity account = accounts.findById(tenantId)
            .orElseGet(() -> accounts.save(new BillingAccountEntity(tenantId, null, plan)));
        account.setPlan(plan);
        recordEvent(tenantId, PLAN_CHANGED, "{\"from\":\"" + previous + "\",\"to\":\"" + plan + "\"}");
    }
}
