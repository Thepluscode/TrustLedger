import { describe, expect, it } from "vitest";
import { needsAttention, positions, roleTone, stateLabel, stateTone, utc, whenText } from "./timeline";
import type { PaymentTimelineView, TimelineEvent } from "./types";

// Expected values are written out here, not read from timeline.ts.
function event(over: Partial<TimelineEvent>): TimelineEvent {
  return {
    eventId: "rec:a", position: 1, kind: "PROVIDER_EVENT", eventType: "CHARGE", provider: "provider-b", status: "SUCCESS",
    refs: { providerEventId: null, stableRef: null, internalRef: null, settlementBatch: null },
    occurred: null, booked: null, receivedAt: null, placement: "BY_SOURCE_TIME", arrivedOutOfOrder: false, amounts: [], fxRate: null,
    role: "MATCHED", roleReason: null, duplicateOf: null, linkedBy: null, derivedInto: null, matches: [], findingIds: [],
    evidence: { importId: "i", sourceType: null, sourceIdentity: null, filename: null, profile: null, fileSha256: null, rowNumber: 1,
      rowSha256: "x", storageKey: null, rawRow: null, intact: true, deliveryCount: 1, feedId: null, importedAt: null, inLatestRun: true },
    ...over,
  };
}

describe("payment timeline wording", () => {
  it("never colours an unknown, stale or unrecognised state as fine", () => {
    expect(stateTone("NO_DISCREPANCY_FOUND")).toBe("ok");
    expect(["OUTCOME_UNKNOWN", "NOT_RECONCILED", "DISCREPANCY_OPEN", "EVIDENCE_INTEGRITY_FAILED", "SOMETHING_NEW"].map(stateTone))
      .toEqual(["warn", "warn", "bad", "bad", "warn"]);
    expect(stateLabel("OUTCOME_UNKNOWN")).toBe("Outcome unknown");
    expect(stateLabel("SOMETHING_NEW")).toBe("something new");
    expect(roleTone("A_ROLE_ADDED_LATER")).toBe("warn");
  });

  it("shows a placed time to the second in UTC and keeps an unplaced one as written", () => {
    expect(utc("2026-08-03T10:00:02Z")).toBe("2026-08-03 10:00:02 UTC");
    expect(whenText(event({ occurred: { raw: null, source: "RECORD", zoneEvidence: null, instant: "2026-08-03T10:00:00Z", unresolvedReason: null } })))
      .toBe("2026-08-03 10:00:00 UTC");
    expect(whenText(event({ occurred: { raw: "2022-11-14 12:08:07", source: "UNRESOLVED", zoneEvidence: null, instant: null, unresolvedReason: "NO_ZONE_EVIDENCE" } })))
      .toBe("2022-11-14 12:08:07 · not placed (no zone evidence)");
    expect(whenText(event({
      occurred: { raw: "2022-11-14 12:08:07", source: "UNRESOLVED", zoneEvidence: null, instant: null, unresolvedReason: "NO_ZONE_EVIDENCE" },
      booked: { raw: "2023-08-01 00:59:59", source: "COLUMN", zoneEvidence: "CEST", instant: "2023-07-31T22:59:59Z", unresolvedReason: null },
    }))).toBe("2023-07-31 22:59:59 UTC (booked)");
    expect(whenText(event({}))).toBe("no time supplied");
  });

  it("flags what an operator must look at and nothing else", () => {
    expect(needsAttention(event({}))).toBe(false);
    expect(needsAttention(event({ findingIds: ["f"] }))).toBe(true);
    expect(needsAttention(event({ role: "DUPLICATE_DELIVERY" }))).toBe(true);
    expect(needsAttention(event({ role: "UNMATCHED" }))).toBe(true);
    expect(needsAttention(event({ evidence: { ...event({}).evidence, intact: false } }))).toBe(true);
  });

  it("maps every item to its position", () => {
    const view = { timeline: [event({ eventId: "rec:a", position: 1 }), event({ eventId: "row:b", position: 2 })] } as PaymentTimelineView;
    expect(positions(view)).toEqual({ "rec:a": 1, "row:b": 2 });
  });
});
