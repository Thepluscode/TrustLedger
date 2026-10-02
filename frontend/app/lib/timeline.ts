import type { PaymentTimelineView, TimelineEvent, TimelineTime } from "./types";

/**
 * Console-side wording for the payment timeline. The server decides the state, the roles and the order;
 * nothing here re-derives them. These maps only choose words and colours, and an unknown value falls back
 * to the server's own text rather than to a reassuring default.
 */
const STATE: Record<string, { label: string; tone: string }> = {
  NO_DISCREPANCY_FOUND: { label: "No discrepancy found", tone: "ok" },
  DISCREPANCY_DECIDED: { label: "Discrepancy decided", tone: "info" },
  DISCREPANCY_OPEN: { label: "Discrepancy open", tone: "bad" },
  OUTCOME_UNKNOWN: { label: "Outcome unknown", tone: "warn" },
  NOT_RECONCILED: { label: "Not reconciled", tone: "warn" },
  EVIDENCE_INTEGRITY_FAILED: { label: "Evidence integrity failed", tone: "bad" },
};

export function stateLabel(state: string): string {
  return STATE[state]?.label ?? state.replace(/_/g, " ").toLowerCase();
}

/** Pill tone. Anything unrecognised is a warning, never "ok". */
export function stateTone(state: string): string {
  return STATE[state]?.tone ?? "warn";
}

const KIND: Record<string, string> = {
  INTERNAL_RECORD: "Internal record",
  PROVIDER_EVENT: "Provider event",
  SETTLEMENT_RECORD: "Settlement",
  PROVIDER_REPORT_ROW: "Provider report row",
  DUPLICATE_ROW: "Duplicate row",
};

export function kindLabel(kind: string): string {
  return KIND[kind] ?? kind.replace(/_/g, " ").toLowerCase();
}

const ROLE: Record<string, { label: string; tone: string }> = {
  MATCHED: { label: "matched", tone: "ok" },
  UNMATCHED: { label: "matched to nothing", tone: "bad" },
  DUPLICATE_DELIVERY: { label: "duplicate delivery · not counted", tone: "warn" },
  DUPLICATE_ROW: { label: "duplicate row · not counted", tone: "warn" },
  NOT_IN_LATEST_RUN: { label: "not yet reconciled", tone: "warn" },
  FEEDS_RECORD: { label: "source of a settlement record", tone: "info" },
  EVIDENCE_ONLY: { label: "kept as evidence · not compared", tone: "info" },
};

export function roleLabel(role: string): string {
  return ROLE[role]?.label ?? role.replace(/_/g, " ").toLowerCase();
}

export function roleTone(role: string): string {
  return ROLE[role]?.tone ?? "warn";
}

/** An instant as exact UTC text to the second. Localising it would hide the two-second gaps that matter here. */
export function utc(iso: string): string {
  return `${iso.slice(0, 10)} ${iso.slice(11, 19)} UTC`;
}

function unplaced(t: TimelineTime): string {
  return `${t.raw ?? "no time supplied"} · not placed (${(t.unresolvedReason ?? "unresolved").replace(/_/g, " ").toLowerCase()})`;
}

/** When an item happened, or its source text and the reason it could not be placed. Never a guessed time. */
export function whenText(e: Pick<TimelineEvent, "occurred" | "booked">): string {
  if (e.occurred?.instant) return utc(e.occurred.instant);
  if (e.booked?.instant) return `${utc(e.booked.instant)} (booked)`;
  const t = e.occurred ?? e.booked;
  return t ? unplaced(t) : "no time supplied";
}

/** eventId → its position on the page, so a finding or a match can point at "#4" instead of a hash. */
export function positions(view: PaymentTimelineView): Record<string, number> {
  const out: Record<string, number> = {};
  for (const e of view.timeline) out[e.eventId] = e.position;
  return out;
}

/** True when the item needs the operator's eye: cited by a finding, a duplicate, unmatched, or failing its hash. */
export function needsAttention(e: TimelineEvent): boolean {
  return e.findingIds.length > 0 || !e.evidence.intact || e.role === "UNMATCHED" || e.role === "DUPLICATE_DELIVERY";
}
