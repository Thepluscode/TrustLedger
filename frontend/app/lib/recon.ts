/**
 * Console-side mirror of the exception lifecycle. The server is the authority and refuses anything not in
 * its table; this copy only decides which buttons to offer, so an operator is not shown an action that
 * will be refused.
 */
export const WORKING_TRANSITIONS: Record<string, string[]> = {
  OPEN: [],
  ASSIGNED: ["INVESTIGATING"],
  INVESTIGATING: ["AWAITING_EVIDENCE", "ASSIGNED"],
  AWAITING_EVIDENCE: ["INVESTIGATING"],
  RESOLVED: [],
  DISMISSED: [],
};

export interface ResolutionReason {
  code: string;
  label: string;
  closesAs: "RESOLVED" | "DISMISSED";
  evidenceRequired: boolean;
}

export const RESOLUTION_REASONS: ResolutionReason[] = [
  { code: "RECOVERED", label: "Recovered — the money landed", closesAs: "RESOLVED", evidenceRequired: false },
  { code: "INTERNAL_CORRECTED", label: "Internal record corrected", closesAs: "RESOLVED", evidenceRequired: false },
  { code: "PROVIDER_CORRECTED", label: "Provider corrected — evidence required", closesAs: "RESOLVED", evidenceRequired: true },
  { code: "WRITTEN_OFF", label: "Written off — evidence required", closesAs: "RESOLVED", evidenceRequired: true },
  { code: "FALSE_POSITIVE", label: "False positive — no real break", closesAs: "DISMISSED", evidenceRequired: false },
  { code: "DUPLICATE", label: "Duplicate of another exception", closesAs: "DISMISSED", evidenceRequired: false },
  { code: "OUT_OF_SCOPE", label: "Out of scope for this case", closesAs: "DISMISSED", evidenceRequired: false },
];

export function isClosed(lifecycleState: string): boolean {
  return lifecycleState === "RESOLVED" || lifecycleState === "DISMISSED";
}

/** Why the resolve button is disabled, or null when the decision can be submitted. */
export function resolutionBlocker(reasonCode: string, note: string, evidenceRef: string): string | null {
  const reason = RESOLUTION_REASONS.find((r) => r.code === reasonCode);
  if (!reason) return "Choose a reason";
  if (!note.trim()) return "Explain the decision";
  if (reason.evidenceRequired && !evidenceRef) return "Attach evidence and select it";
  return null;
}

/** "90.9%" from "0.9091". null stays null: no expected payments means no rate, not 0%. */
export function matchRatePercent(rate: string | null): string | null {
  if (rate === null || rate === "") return null;
  return `${(Number(rate) * 100).toFixed(1)}%`;
}

export interface RunSummary {
  exceptionsByType: Record<string, number>;
  matchesByRule: Record<string, number>;
  settlementCoveredProviders: string[];
  providersWithoutSettlementFile: string[];
  feesChecked: number;
}

export function parseRunSummary(json: string): RunSummary {
  const s = JSON.parse(json) as Partial<RunSummary>;
  return {
    exceptionsByType: s.exceptionsByType ?? {},
    matchesByRule: s.matchesByRule ?? {},
    settlementCoveredProviders: s.settlementCoveredProviders ?? [],
    providersWithoutSettlementFile: s.providersWithoutSettlementFile ?? [],
    feesChecked: s.feesChecked ?? 0,
  };
}

export interface EvidenceRecordLink {
  recordId: string;
  recordKey: string;
  sourceType: string;
  sourceSystem: string;
  rowNumber: number;
  fileSha256: string;
}

/** The source rows an exception cites, grouped by which side of the comparison they came from. */
export function recordsBySide(evidenceJson: string): Record<string, EvidenceRecordLink[]> {
  let records: EvidenceRecordLink[] = [];
  try {
    records = (JSON.parse(evidenceJson) as { records?: EvidenceRecordLink[] }).records ?? [];
  } catch {
    return {};
  }
  const sides: Record<string, EvidenceRecordLink[]> = {};
  for (const r of records) (sides[r.sourceType] ??= []).push(r);
  return sides;
}

export const IMPORT_PROFILES: { sourceType: string; profile: string; label: string }[] = [
  { sourceType: "INTERNAL", profile: "internal-expected", label: "Internal ledger — expected payments" },
  { sourceType: "PROVIDER_TRANSACTION", profile: "provider-transactions", label: "Provider — transactions" },
  { sourceType: "SETTLEMENT", profile: "provider-settlement", label: "Provider — settlement" },
];
