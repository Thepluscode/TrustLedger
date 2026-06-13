package com.trustledger.app;

import com.trustledger.core.idempotency.IdempotencyService;
import com.trustledger.persistence.entity.TransferMfaChallengeEntity;
import com.trustledger.persistence.repo.TransferMfaChallengeRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inline step-up (MFA) challenges for transfers in the MFA risk band. A 6-digit code is issued
 * (hash-stored, plaintext delivered out-of-band), verified with bounded attempts and a TTL, and
 * drives the transfer's resume (verify) or release (exhaust/expire). Stateless w.r.t. the transfer:
 * the gateway maps the verify result onto the existing approve (resume) / reject (release) paths.
 */
@Service
public class TransferMfaService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long TTL_MINUTES = 15;

    private final TransferMfaChallengeRepository challenges;
    private final SecureRandom random = new SecureRandom();

    public TransferMfaService(TransferMfaChallengeRepository challenges) {
        this.challenges = challenges;
    }

    public enum Result { VERIFIED, INVALID_CODE, EXPIRED, EXHAUSTED, NO_CHALLENGE }

    /** Issue a fresh challenge for a transfer, superseding any prior pending one. Returns the plaintext code. */
    @Transactional
    public String issue(UUID tenantId, UUID userId, UUID transferId) {
        challenges.findByTransferIdAndStatus(transferId, "PENDING").ifPresent(c -> c.setStatus("SUPERSEDED"));
        String code = String.format("%06d", random.nextInt(1_000_000));
        challenges.save(new TransferMfaChallengeEntity(UUID.randomUUID(), tenantId, transferId, userId,
            IdempotencyService.sha256(code), "PENDING", 0, MAX_ATTEMPTS,
            Instant.now().plus(TTL_MINUTES, ChronoUnit.MINUTES)));
        return code;
    }

    /** Verify a submitted code against the transfer's pending challenge, enforcing TTL + attempt cap. */
    @Transactional
    public Result verify(UUID tenantId, UUID transferId, String code) {
        Optional<TransferMfaChallengeEntity> opt = challenges.findByTransferIdAndStatus(transferId, "PENDING");
        if (opt.isEmpty() || !opt.get().getTenantId().equals(tenantId)) return Result.NO_CHALLENGE;
        TransferMfaChallengeEntity c = opt.get();
        if (c.getExpiresAt().isBefore(Instant.now())) {
            c.setStatus("EXPIRED");
            return Result.EXPIRED;
        }
        if (code != null && IdempotencyService.sha256(code).equals(c.getCodeHash())) {
            c.setStatus("VERIFIED");
            return Result.VERIFIED;
        }
        c.setAttempts(c.getAttempts() + 1);
        if (c.getAttempts() >= c.getMaxAttempts()) {
            c.setStatus("EXHAUSTED");
            return Result.EXHAUSTED;
        }
        return Result.INVALID_CODE;
    }
}
