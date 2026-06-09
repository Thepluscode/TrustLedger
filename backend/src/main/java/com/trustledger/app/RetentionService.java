package com.trustledger.app;

import com.trustledger.persistence.entity.EvidenceExportEntity;
import com.trustledger.persistence.entity.RetentionPolicyEntity;
import com.trustledger.persistence.repo.EvidenceExportRepository;
import com.trustledger.persistence.repo.RetentionPolicyRepository;
import com.trustledger.security.ForbiddenException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Retention + legal hold. Legal hold (on the export or its resource policy) blocks deletion. */
@Service
public class RetentionService {

    private final EvidenceExportRepository exports;
    private final RetentionPolicyRepository policies;

    public RetentionService(EvidenceExportRepository exports, RetentionPolicyRepository policies) {
        this.exports = exports;
        this.policies = policies;
    }

    @Transactional
    public RetentionPolicyEntity upsertPolicy(UUID tenantId, String resourceType, int retentionDays,
                                              boolean archiveEnabled, String deletionMode, boolean legalHoldEnabled) {
        RetentionPolicyEntity p = policies.findByTenantIdAndResourceType(tenantId, resourceType).orElse(null);
        if (p == null) {
            return policies.save(new RetentionPolicyEntity(UUID.randomUUID(), tenantId, resourceType,
                retentionDays, archiveEnabled, deletionMode, legalHoldEnabled));
        }
        p.setRetentionDays(retentionDays);
        p.setArchiveEnabled(archiveEnabled);
        p.setDeletionMode(deletionMode);
        p.setLegalHoldEnabled(legalHoldEnabled);
        return p;
    }

    @Transactional
    public void setLegalHold(UUID tenantId, UUID exportId, boolean on) {
        require(tenantId, exportId).setLegalHold(on);
    }

    /** Deletes an evidence export unless it (or its resource-type policy) is under legal hold. */
    @Transactional
    public void deleteExport(UUID tenantId, UUID exportId) {
        EvidenceExportEntity export = require(tenantId, exportId);
        boolean policyHold = policies.findByTenantIdAndResourceType(tenantId, export.getResourceType())
            .map(RetentionPolicyEntity::isLegalHoldEnabled).orElse(false);
        if (export.isLegalHold() || policyHold) {
            throw new ForbiddenException("Evidence is under legal hold and cannot be deleted");
        }
        exports.delete(export);
    }

    private EvidenceExportEntity require(UUID tenantId, UUID exportId) {
        EvidenceExportEntity e = exports.findById(exportId)
            .orElseThrow(() -> new IllegalArgumentException("Export not found: " + exportId));
        if (!e.getTenantId().equals(tenantId)) throw new ForbiddenException("Evidence belongs to another tenant");
        return e;
    }
}
