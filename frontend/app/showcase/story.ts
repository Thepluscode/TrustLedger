import type { PaymentTimelineView, TimelineEvent, TimelineEvidence, TimelineFinding } from "../lib/types";

/**
 * SYNTHETIC. The GBP 50,000 story of the executive showcase, written in the exact shape the timeline
 * API returns, so the public page renders it with the same component the console uses for a real
 * case. Every value here is fictional: the provider, the references, the hashes, the actors. Nothing
 * is fetched, and nothing on this page is customer or production evidence.
 *
 * The story, in the order the evidence happened:
 *   08:40:51  internal ledger expects GBP 50,000 through provider-a
 *   08:41:06  provider-a charges GBP 50,000, fee 850, net 49,150 (event evt_500001)
 *   08:41:14  provider-a delivers evt_500001 again; it is kept, marked, not counted
 *   next day  settlement batch ST-5001 pays out 49,150
 *   the run   the contracted fee was 250 (0.5%); the finding is FEE_MISMATCH, GBP 600 at risk
 *   people    the duplicate was examined and closed; the fee is under investigation with provider-a
 */

const RUN_ID = "7d2c6a1e-4b0f-4b7e-9d53-8f1e2c3a4b5d";
const FEE_FINDING = "a2f8000e-1c44-4a0b-8d1e-5f6a7b8c9d0e";
const DUP_FINDING = "ab8cf5a7-3e21-4c9f-b0a2-7c6d5e4f3a2b";
const OPERATOR = "4c1d9e2f-6a7b-4c8d-9e0f-1a2b3c4d5e6f";

function evidence(importId: string, sourceType: string, sourceIdentity: string, filename: string, profile: string,
                  fileSha256: string, rowNumber: number, rowSha256: string, rawRow: string, importedAt: string,
                  deliveryCount = 1): TimelineEvidence {
  return { importId, sourceType, sourceIdentity, filename, profile, fileSha256, rowNumber, rowSha256,
    storageKey: `evidence/demo/recon-import/${importId}/${fileSha256}.csv`, rawRow, intact: true, deliveryCount,
    feedId: null, importedAt, inLatestRun: true };
}

const INTERNAL: TimelineEvent = {
  eventId: "rec:a1b2c3d4e5f60718293a4b5c6d7e8f9001122334455667788990aabbccddeeff",
  position: 1, kind: "INTERNAL_RECORD", eventType: "EXPECTED_PAYMENT", provider: "provider-a", status: "PAID",
  refs: { providerEventId: null, stableRef: "pa_500001", internalRef: "TL-2026-0812-0042", settlementBatch: null },
  occurred: { raw: null, source: "RECORD", zoneEvidence: null, instant: "2026-08-12T08:40:51Z", unresolvedReason: null },
  booked: null, receivedAt: null, placement: "BY_SOURCE_TIME", arrivedOutOfOrder: false,
  amounts: [{ role: "GROSS", currency: "GBP", value: "50000.0000", direction: null, providerField: null, rawValue: null }],
  fxRate: null, role: "MATCHED", roleReason: null, duplicateOf: null, linkedBy: "ANCHOR", derivedInto: null,
  matches: [{ ruleId: "R1-STABLE-ID", ruleVersion: "recon-rules/1.2.0", counterpartEventId: "rec:b2c3d4e5f60718293a4b5c6d7e8f9001122334455667788990aabbccddeeff00" }],
  findingIds: [],
  evidence: evidence("e1a0c4f2-0001-4000-8000-000000000001", "INTERNAL", "acme-ledger", "internal-ledger-2026-08.csv", "internal-expected/v1",
    "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8", 42, "2b13742e3fddf3638e4d5965ccf2fcda12a372c58a444c7764d632a66949853e",
    "TL-2026-0812-0042,provider-a,pa_500001,PAYMENT,GBP,50000.00,2026-08-12T08:40:51Z,PAID", "2026-08-13T15:58:02Z"),
};

const CHARGE: TimelineEvent = {
  eventId: "rec:b2c3d4e5f60718293a4b5c6d7e8f9001122334455667788990aabbccddeeff00",
  position: 2, kind: "PROVIDER_EVENT", eventType: "CHARGE", provider: "provider-a", status: "SUCCESS",
  refs: { providerEventId: "evt_500001", stableRef: "pa_500001", internalRef: "TL-2026-0812-0042", settlementBatch: null },
  occurred: { raw: null, source: "RECORD", zoneEvidence: null, instant: "2026-08-12T08:41:06Z", unresolvedReason: null },
  booked: null, receivedAt: "2026-08-12T08:41:07Z", placement: "BY_SOURCE_TIME", arrivedOutOfOrder: false,
  amounts: [
    { role: "GROSS", currency: "GBP", value: "50000.0000", direction: null, providerField: null, rawValue: null },
    { role: "FEE", currency: "GBP", value: "850.0000", direction: null, providerField: null, rawValue: null },
    { role: "NET", currency: "GBP", value: "49150.0000", direction: null, providerField: null, rawValue: null },
  ],
  fxRate: null, role: "MATCHED", roleReason: null, duplicateOf: null, linkedBy: "ANCHOR", derivedInto: null,
  matches: [
    { ruleId: "R1-STABLE-ID", ruleVersion: "recon-rules/1.2.0", counterpartEventId: INTERNAL.eventId },
    { ruleId: "R3-SETTLEMENT-BATCH", ruleVersion: "recon-rules/1.2.0", counterpartEventId: "rec:d4e5f60718293a4b5c6d7e8f9001122334455667788990aabbccddeeff00112233" },
  ],
  findingIds: [FEE_FINDING, DUP_FINDING],
  evidence: evidence("e1a0c4f2-0002-4000-8000-000000000002", "PROVIDER_TRANSACTION", "provider-a", "provider-a-transactions-2026-08.csv", "provider-transactions/v1",
    "8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4", 118, "504c407f85b501f5ae0c3d5722501785f5c6c73f83b21146f01adb3500425585",
    "evt_500001,pa_500001,TL-2026-0812-0042,CHARGE,SUCCESS,GBP,50000.00,850.00,49150.00,2026-08-12T08:41:06Z,2026-08-12T08:41:07Z", "2026-08-13T15:58:40Z"),
};

const DUPLICATE: TimelineEvent = {
  ...CHARGE,
  eventId: "rec:c3d4e5f60718293a4b5c6d7e8f9001122334455667788990aabbccddeeff001122",
  position: 3, receivedAt: "2026-08-12T08:41:14Z",
  role: "DUPLICATE_DELIVERY",
  roleReason: "the same provider event id was delivered before; this delivery is evidence and is not counted again",
  duplicateOf: CHARGE.eventId, matches: [], findingIds: [DUP_FINDING],
  evidence: evidence("e1a0c4f2-0002-4000-8000-000000000002", "PROVIDER_TRANSACTION", "provider-a", "provider-a-transactions-2026-08.csv", "provider-transactions/v1",
    "8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4", 119, "7d9a4b0c1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b",
    "evt_500001,pa_500001,TL-2026-0812-0042,CHARGE,SUCCESS,GBP,50000.00,850.00,49150.00,2026-08-12T08:41:06Z,2026-08-12T08:41:14Z", "2026-08-13T15:58:40Z"),
};

const SETTLEMENT: TimelineEvent = {
  eventId: "rec:d4e5f60718293a4b5c6d7e8f9001122334455667788990aabbccddeeff00112233",
  position: 4, kind: "SETTLEMENT_RECORD", eventType: "SETTLEMENT_LINE", provider: "provider-a", status: "SETTLED",
  refs: { providerEventId: null, stableRef: "pa_500001", internalRef: null, settlementBatch: "ST-5001" },
  occurred: { raw: null, source: "RECORD", zoneEvidence: null, instant: "2026-08-13T16:03:44Z", unresolvedReason: null },
  booked: null, receivedAt: null, placement: "BY_SOURCE_TIME", arrivedOutOfOrder: false,
  amounts: [
    { role: "GROSS", currency: "GBP", value: "50000.0000", direction: null, providerField: null, rawValue: null },
    { role: "FEE", currency: "GBP", value: "850.0000", direction: null, providerField: null, rawValue: null },
    { role: "NET", currency: "GBP", value: "49150.0000", direction: null, providerField: null, rawValue: null },
  ],
  fxRate: null, role: "MATCHED", roleReason: null, duplicateOf: null, linkedBy: "STABLE_REF", derivedInto: null,
  matches: [{ ruleId: "R3-SETTLEMENT-BATCH", ruleVersion: "recon-rules/1.2.0", counterpartEventId: CHARGE.eventId }],
  findingIds: [],
  evidence: evidence("e1a0c4f2-0003-4000-8000-000000000003", "SETTLEMENT", "provider-a", "provider-a-settlement-ST-5001.csv", "provider-settlement/v1",
    "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592", 7, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "ST-5001,pa_500001,GBP,50000.00,850.00,49150.00,2026-08-13T16:03:44Z", "2026-08-13T16:04:10Z"),
};

const FEE: TimelineFinding = {
  exceptionId: FEE_FINDING, type: "FEE_MISMATCH", classification: "FEE_MISMATCH", severity: "HIGH",
  expected: "fee 250.0000 GBP (tolerance 0.0100)", actual: "fee 850.0000 GBP",
  explanation: "The fee on pa_500001 differs from the agreed schedule by 600.0000 GBP (overcharged).",
  exposureAmount: "600.0000", exposureCurrency: "GBP", ruleId: "D-FEE-SCHEDULE", ruleVersion: "recon-rules/1.2.0",
  raisedByRunId: RUN_ID, raisedByLatestRun: true, raisedAt: "2026-08-13T16:05:12Z", open: true, lifecycleState: "INVESTIGATING",
  ownerUserId: OPERATOR, dueAt: "2026-08-14T16:05:12Z", decision: null, eventIds: [CHARGE.eventId],
  history: [
    { seq: 1, kind: "RAISED", fromState: null, toState: "OPEN", actorId: null, body: "The fee on pa_500001 differs from the agreed schedule by 600.0000 GBP (overcharged).", evidenceSha256: null, evidenceFilename: null, at: "2026-08-13T16:05:12Z" },
    { seq: 2, kind: "ASSIGNED", fromState: "OPEN", toState: "ASSIGNED", actorId: OPERATOR, body: "ops@acme.example", evidenceSha256: null, evidenceFilename: null, at: "2026-08-13T16:20:40Z" },
    { seq: 3, kind: "TRANSITION", fromState: "ASSIGNED", toState: "INVESTIGATING", actorId: OPERATOR, body: null, evidenceSha256: null, evidenceFilename: null, at: "2026-08-13T16:21:03Z" },
    { seq: 4, kind: "EVIDENCE_ADDED", fromState: "INVESTIGATING", toState: "INVESTIGATING", actorId: OPERATOR, body: "Signed fee schedule, 0.5% flat, in force since 2026-01-01", evidenceSha256: "9c1185a5c5e9fc54612808977ee8f548b2258d31f3a5e5a6c0b8a5e8b1b4c0d2", evidenceFilename: "provider-a-fee-schedule-2026.pdf", at: "2026-08-13T16:34:19Z" },
    { seq: 5, kind: "COMMENT", fromState: "INVESTIGATING", toState: "INVESTIGATING", actorId: OPERATOR, body: "provider-a confirms 1.7% was applied in error; a credit note of GBP 600.00 is promised. Stays open until it lands.", evidenceSha256: null, evidenceFilename: null, at: "2026-08-14T09:12:55Z" },
  ],
};

const DUP: TimelineFinding = {
  exceptionId: DUP_FINDING, type: "DUPLICATE_PROVIDER_EVENT", classification: "DUPLICATE_TRANSACTION", severity: "MEDIUM",
  expected: "one delivery of event evt_500001", actual: "2 deliveries",
  explanation: "Provider event evt_500001 appears 2 times. The first delivery is used; the rest are kept as evidence and counted once. At risk is the amount that would be double-counted if both were applied.",
  exposureAmount: "50000.0000", exposureCurrency: "GBP", ruleId: "D-DUPLICATE-EVENT", ruleVersion: "recon-rules/1.2.0",
  raisedByRunId: RUN_ID, raisedByLatestRun: true, raisedAt: "2026-08-13T16:05:12Z", open: false, lifecycleState: "DISMISSED",
  ownerUserId: OPERATOR, dueAt: "2026-08-14T16:05:12Z",
  decision: { closedAs: "DISMISSED", reasonCode: "FALSE_POSITIVE", explanation: "The second delivery was never applied: one charge, one settlement line, one ledger expectation. No money was counted twice.", decidedBy: OPERATOR, decidedAt: "2026-08-13T16:28:07Z" },
  eventIds: [DUPLICATE.eventId, CHARGE.eventId],
  history: [
    { seq: 1, kind: "RAISED", fromState: null, toState: "OPEN", actorId: null, body: "Provider event evt_500001 appears 2 times.", evidenceSha256: null, evidenceFilename: null, at: "2026-08-13T16:05:12Z" },
    { seq: 2, kind: "ASSIGNED", fromState: "OPEN", toState: "ASSIGNED", actorId: OPERATOR, body: "ops@acme.example", evidenceSha256: null, evidenceFilename: null, at: "2026-08-13T16:20:40Z" },
    { seq: 3, kind: "DISMISSED", fromState: "ASSIGNED", toState: "DISMISSED", actorId: OPERATOR, body: "The second delivery was never applied: one charge, one settlement line, one ledger expectation. No money was counted twice.", evidenceSha256: null, evidenceFilename: null, at: "2026-08-13T16:28:07Z" },
  ],
};

/** The showcase story as the timeline API would return it. Fictional throughout. */
export const FIFTY_THOUSAND_STORY: PaymentTimelineView = {
  payment: { ref: "TL-2026-0812-0042", providers: ["provider-a"], stableRefs: ["pa_500001"], internalRefs: ["TL-2026-0812-0042"], currencies: ["GBP"] },
  conclusion: {
    state: "DISCREPANCY_OPEN",
    statement: "1 open finding(s): FEE_MISMATCH. At risk: GBP 600.0000.",
    openFindingTypes: ["FEE_MISMATCH"],
    decidedFindingTypes: ["DUPLICATE_PROVIDER_EVENT"],
    openExposureByCurrency: [{ currency: "GBP", amount: "600.0000" }],
    notes: [],
    // A real run key is a SHA-256 of the inputs; this one reads as what it is, and does not look like a credential.
    basis: { runId: RUN_ID, runKey: "synthetic-showcase-run".padEnd(64, "0"), rulesetVersion: "recon-rules/1.2.0", completedAt: "2026-08-13T16:05:12Z" },
  },
  timeline: [INTERNAL, CHARGE, DUPLICATE, SETTLEMENT],
  findings: [DUP, FEE],
};
