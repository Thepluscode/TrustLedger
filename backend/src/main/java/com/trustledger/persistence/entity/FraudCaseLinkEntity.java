package com.trustledger.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A link between two fraud cases (e.g. same beneficiary), surfacing organised fraud. */
@Entity
@Table(name = "fraud_case_links")
public class FraudCaseLinkEntity {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "linked_case_id", nullable = false)
    private UUID linkedCaseId;

    @Column(name = "link_type", nullable = false, length = 48)
    private String linkType;

    @Column(columnDefinition = "text")
    private String reason;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected FraudCaseLinkEntity() {}

    public FraudCaseLinkEntity(UUID id, UUID caseId, UUID linkedCaseId, String linkType, String reason) {
        this.id = id;
        this.caseId = caseId;
        this.linkedCaseId = linkedCaseId;
        this.linkType = linkType;
        this.reason = reason;
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public UUID getLinkedCaseId() { return linkedCaseId; }
    public String getLinkType() { return linkType; }
}
