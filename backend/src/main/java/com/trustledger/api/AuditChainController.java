package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.app.AuditChainService;
import com.trustledger.app.AuditChainService.SealResult;
import com.trustledger.app.AuditChainService.VerificationResult;
import com.trustledger.persistence.entity.AuditCheckpointEntity;
import com.trustledger.security.Permission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Audit tamper-evidence. Verification is a read (AUDIT_VIEW) — an auditor must be able to check the
 * chain without the power to extend it. Sealing on demand is an administrative action (TENANT_ADMIN);
 * it normally happens on a schedule.
 *
 * <p>The chain covers the whole audit table rather than one tenant's slice: the threat is a
 * privileged operator editing rows, and that operator is not confined to a tenant. Nothing
 * tenant-identifying is returned — only hashes, counts and window bounds.
 */
@RestController
@RequestMapping("/api/v1/audit/chain")
public class AuditChainController {

    private final AuditChainService chain;
    private final AccessControlService access;

    public AuditChainController(AuditChainService chain, AccessControlService access) {
        this.chain = chain;
        this.access = access;
    }

    public record CheckpointView(UUID id, long sequence, Instant windowStart, Instant windowEnd,
                                 int rowCount, String rowsDigest, String prevHash, String checkpointHash,
                                 Instant sealedAt) {}

    @GetMapping("/verify")
    public VerificationResult verify() {
        access.require(Permission.AUDIT_VIEW);
        return chain.verify();
    }

    @GetMapping("/checkpoints")
    public List<CheckpointView> checkpoints() {
        access.require(Permission.AUDIT_VIEW);
        return chain.list().stream()
                .map(c -> new CheckpointView(c.getId(), c.getSequence(), c.getWindowStart(), c.getWindowEnd(),
                        c.getRowCount(), c.getRowsDigest(), c.getPrevHash(), c.getCheckpointHash(), c.getSealedAt()))
                .toList();
    }

    @PostMapping("/seal")
    public SealResult seal() {
        access.require(Permission.TENANT_ADMIN);
        return chain.seal();
    }
}
