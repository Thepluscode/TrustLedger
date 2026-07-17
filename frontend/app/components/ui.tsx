"use client";

import { useState, type ReactNode } from "react";

/** Risk badge — band + score, never colour alone (design.md §21.3). */
export function RiskBadge({ score }: { score: number }) {
  const band = score >= 85 ? "critical" : score >= 60 ? "high" : score >= 30 ? "medium" : "low";
  const label = band.charAt(0).toUpperCase() + band.slice(1);
  return (
    <span className={`risk ${band}`}>
      {label} · {score}
    </span>
  );
}

const STATUS_TONE: Record<string, string> = {
  COMPLETED: "completed",
  SETTLED: "settled",
  ACTIVE: "ok",
  ENABLED: "ok",
  READY: "ok",
  APPROVED: "ok",
  OPEN: "warn",
  HELD_FOR_REVIEW: "held",
  PENDING: "pending",
  PENDING_APPROVAL: "pending",
  PENDING_UNKNOWN: "pending",
  PENDING_SETTLEMENT: "pending",
  EXHAUSTED: "warn",
  PAUSED: "held",
  DISABLED: "failed",
  BLOCKED: "failed",
  FAILED: "failed",
  REJECTED: "rejected",
  REVERSED: "warn",
  REVOKED: "bad",
  EXPIRED: "warn",
  FROZEN: "bad",
  SHADOW: "info",
};

/** Status pill with semantic dot (design.md §21.2). Unknown statuses render neutral. */
export function StatusPill({ value }: { value: string }) {
  const tone = STATUS_TONE[value.toUpperCase()] ?? "";
  return <span className={`pill ${tone}`}>{value.replace(/_/g, " ")}</span>;
}

const SEV_CLASS: Record<string, string> = {
  LOW: "low",
  MEDIUM: "medium",
  HIGH: "high",
  CRITICAL: "critical",
};

export function SeverityPill({ value }: { value: string }) {
  return <span className={`risk ${SEV_CLASS[value.toUpperCase()] ?? "low"}`}>{value}</span>;
}

/** Smart empty state (design.md §23.2) — never bare "No data." */
export function EmptyState({ title, hint }: { title: string; hint: string }) {
  return (
    <div className="empty">
      <div className="title">{title}</div>
      <div>{hint}</div>
    </div>
  );
}

/**
 * Dangerous-action confirmation (design.md §2.3): a modal that requires typing a word
 * (e.g. APPROVE / REJECT / EXPORT) before the destructive button enables.
 */
export function ConfirmModal({
  open,
  title,
  body,
  confirmWord,
  confirmLabel,
  danger,
  busy,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: string;
  body: ReactNode;
  confirmWord: string;
  confirmLabel: string;
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const [typed, setTyped] = useState("");
  if (!open) return null;
  const matched = typed.trim().toUpperCase() === confirmWord.toUpperCase();
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label={title}>
      <div className="modal">
        <h3>{title}</h3>
        <div className="muted">{body}</div>
        <div className="typed">
          <label>
            Type <kbd>{confirmWord}</kbd> to confirm — this action is audited
          </label>
          <input
            autoFocus
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            placeholder={confirmWord}
          />
        </div>
        <div className="actions">
          <button className="secondary" onClick={() => { setTyped(""); onCancel(); }}>
            Cancel
          </button>
          <button
            className={danger ? "danger" : ""}
            disabled={!matched || busy}
            onClick={() => { setTyped(""); onConfirm(); }}
          >
            {busy ? "Working…" : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Loading skeleton rows for tables/cards. */
export function SkeletonRows({ cols, rows = 4 }: { cols: number; rows?: number }) {
  return (
    <>
      {Array.from({ length: rows }, (_, r) => (
        <tr key={r}>
          {Array.from({ length: cols }, (_, c) => (
            <td key={c}>
              <div className="skeleton" style={{ width: `${55 + ((r + c) % 3) * 15}%` }} />
            </td>
          ))}
        </tr>
      ))}
    </>
  );
}
