package com.trustledger.app;

import com.trustledger.persistence.entity.FraudCaseEntity;
import com.trustledger.persistence.entity.FraudCaseLinkEntity;
import com.trustledger.persistence.entity.TransferEntity;
import com.trustledger.persistence.repo.FraudCaseLinkRepository;
import com.trustledger.persistence.repo.FraudCaseRepository;
import com.trustledger.persistence.repo.TransferRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Links fraud cases that share a recipient account — the organised-fraud / mule signal. */
@Service
public class FraudCaseLinkingService {

    private static final String SAME_BENEFICIARY = "SAME_BENEFICIARY";

    private final FraudCaseRepository fraudCases;
    private final TransferRepository transfers;
    private final FraudCaseLinkRepository caseLinks;

    public FraudCaseLinkingService(FraudCaseRepository fraudCases, TransferRepository transfers,
                                   FraudCaseLinkRepository caseLinks) {
        this.fraudCases = fraudCases;
        this.transfers = transfers;
        this.caseLinks = caseLinks;
    }

    /** Links a newly-opened case to every other case targeting the same recipient. Returns links created. */
    @Transactional
    public int linkNewCase(UUID caseId) {
        FraudCaseEntity current = fraudCases.findById(caseId).orElse(null);
        if (current == null) return 0;
        TransferEntity transfer = transfers.findById(current.getTransactionId()).orElse(null);
        if (transfer == null) return 0;
        UUID recipient = transfer.getDestinationAccountId();

        int created = 0;
        for (TransferEntity sibling : transfers.findByDestinationAccountId(recipient)) {
            if (sibling.getId().equals(transfer.getId())) continue;
            FraudCaseEntity other = fraudCases.findByTransactionId(sibling.getId()).orElse(null);
            if (other == null || other.getId().equals(caseId)) continue;
            created += link(caseId, other.getId(), recipient);
            link(other.getId(), caseId, recipient); // bidirectional for discovery
        }
        return created;
    }

    private int link(UUID from, UUID to, UUID recipient) {
        if (caseLinks.existsByCaseIdAndLinkedCaseIdAndLinkType(from, to, SAME_BENEFICIARY)) return 0;
        caseLinks.save(new FraudCaseLinkEntity(UUID.randomUUID(), from, to, SAME_BENEFICIARY,
            "Same recipient account " + recipient));
        return 1;
    }
}
