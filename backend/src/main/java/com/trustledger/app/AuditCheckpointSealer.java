package com.trustledger.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Seals audit checkpoints on a schedule. Separate from {@link AuditChainService} so that sealing can
 * be disabled (tests, one-off tooling) without disabling the ability to seal or verify on demand.
 *
 * <p>Failure here is logged and swallowed on purpose: an unsealed window delays tamper-evidence, but
 * an exception escaping a scheduled method would stop the schedule entirely and silently — the worse
 * outcome. The gap is visible either way, because verification reports unprotected rows.
 */
@Component
public class AuditCheckpointSealer {

    private static final Logger log = LoggerFactory.getLogger(AuditCheckpointSealer.class);

    private final AuditChainService chain;
    private final boolean enabled;

    public AuditCheckpointSealer(AuditChainService chain,
                                 @Value("${trustledger.audit.checkpoint.enabled:true}") boolean enabled) {
        this.chain = chain;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${trustledger.audit.checkpoint.interval-ms:300000}")
    public void scheduledSeal() {
        if (!enabled) return;
        try {
            AuditChainService.SealResult result = chain.seal();
            if (result.sealed()) {
                log.info("Audit checkpoint {} sealed ({} rows)", result.sequence(), result.rowCount());
            } else {
                log.debug("Audit checkpoint not sealed: {}", result.reason());
            }
        } catch (RuntimeException e) {
            log.error("Audit checkpoint sealing failed — rows stay unprotected until the next run", e);
        }
    }
}
