import { describe, expect, it } from "vitest";
import { RESOLUTION_REASONS, WORKING_TRANSITIONS, isClosed, matchRatePercent, parseRunSummary, recordsBySide, resolutionBlocker } from "./recon";

// Expected values are written out here, not derived from recon.ts, so a dropped reason or a widened
// transition table fails this file.
describe("exception lifecycle, as the console offers it", () => {
  it("offers exactly the working transitions the server allows", () => {
    expect(WORKING_TRANSITIONS).toEqual({
      OPEN: [],
      ASSIGNED: ["INVESTIGATING"],
      INVESTIGATING: ["AWAITING_EVIDENCE", "ASSIGNED"],
      AWAITING_EVIDENCE: ["INVESTIGATING"],
      RESOLVED: [],
      DISMISSED: [],
    });
  });

  it("knows seven reasons, which close as which state, and which two need evidence", () => {
    expect(RESOLUTION_REASONS.map((r) => `${r.code}:${r.closesAs}:${r.evidenceRequired}`).sort()).toEqual([
      "DUPLICATE:DISMISSED:false",
      "FALSE_POSITIVE:DISMISSED:false",
      "INTERNAL_CORRECTED:RESOLVED:false",
      "OUT_OF_SCOPE:DISMISSED:false",
      "PROVIDER_CORRECTED:RESOLVED:true",
      "RECOVERED:RESOLVED:false",
      "WRITTEN_OFF:RESOLVED:true",
    ]);
  });

  it("treats both terminal states as closed and nothing else", () => {
    expect(["OPEN", "ASSIGNED", "INVESTIGATING", "AWAITING_EVIDENCE", "RESOLVED", "DISMISSED"].filter(isClosed)).toEqual(["RESOLVED", "DISMISSED"]);
  });

  it("blocks a decision with no reason, no explanation, or missing required evidence", () => {
    expect(resolutionBlocker("", "x", "")).toBe("Choose a reason");
    expect(resolutionBlocker("RECOVERED", "   ", "")).toBe("Explain the decision");
    expect(resolutionBlocker("WRITTEN_OFF", "below threshold", "")).toBe("Attach evidence and select it");
    expect(resolutionBlocker("WRITTEN_OFF", "below threshold", "evidence/t/recon-issue/i/abc")).toBeNull();
    expect(resolutionBlocker("RECOVERED", "re-settled", "")).toBeNull();
  });
});

describe("run results", () => {
  it("shows a match rate as a percentage and keeps 'no rate' distinct from 0%", () => {
    expect(matchRatePercent("0.9091")).toBe("90.9%");
    expect(matchRatePercent("0.0000")).toBe("0.0%");
    expect(matchRatePercent(null)).toBeNull();
  });

  it("reads the run summary and survives a field the server did not send", () => {
    const s = parseRunSummary('{"exceptionsByType":{"FEE_MISMATCH":1},"providersWithoutSettlementFile":["provider-a"]}');
    expect(s.exceptionsByType).toEqual({ FEE_MISMATCH: 1 });
    expect(s.providersWithoutSettlementFile).toEqual(["provider-a"]);
    expect(s.matchesByRule).toEqual({});
  });

  it("groups an exception's source rows by side, and malformed evidence yields none", () => {
    const evidence = JSON.stringify({ records: [
      { recordId: "1", recordKey: "k1", sourceType: "INTERNAL", sourceSystem: "acme-ledger", rowNumber: 3, fileSha256: "aa" },
      { recordId: "2", recordKey: "k2", sourceType: "PROVIDER_TRANSACTION", sourceSystem: "provider-b", rowNumber: 3, fileSha256: "bb" },
    ] });
    const sides = recordsBySide(evidence);
    expect(Object.keys(sides).sort()).toEqual(["INTERNAL", "PROVIDER_TRANSACTION"]);
    expect(sides.INTERNAL[0].rowNumber).toBe(3);
    expect(recordsBySide("not json")).toEqual({});
    expect(recordsBySide("{}")).toEqual({});
  });
});
