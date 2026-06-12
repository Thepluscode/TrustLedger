"use client";

import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { ConfirmModal, EmptyState, RiskBadge, SeverityPill, SkeletonRows, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import { shortId } from "../lib/format";
import type { FraudCaseView } from "../lib/types";

const SEV_ORDER: Record<string, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };

type Pending =
  | { kind: "approve" | "reject"; caseId: string }
  | { kind: "export"; caseId: string }
  | null;

export default function FraudCasesPage() {
  const [cases, setCases] = useState<FraudCaseView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending>(null);
  const [busy, setBusy] = useState(false);

  function load() {
    api.listFraudCases().then(setCases).catch((e) => setError((e as Error).message));
  }
  useEffect(load, []);

  // §10.2 priority: severity → risk score; open cases above closed.
  const sorted = (cases ?? []).slice().sort(
    (a, b) =>
      (a.status === "OPEN" ? 0 : 1) - (b.status === "OPEN" ? 0 : 1) ||
      (SEV_ORDER[a.severity] ?? 9) - (SEV_ORDER[b.severity] ?? 9) ||
      b.riskScore - a.riskScore,
  );

  async function runPending() {
    if (!pending) return;
    setBusy(true);
    setError(null);
    setNote(null);
    try {
      if (pending.kind === "approve") {
        const r = await api.approveCase(pending.caseId);
        setNote(`Transfer ${shortId(r.transactionId)} approved — held funds posted to the ledger.`);
      } else if (pending.kind === "reject") {
        const r = await api.rejectCase(pending.caseId);
        setNote(`Transfer ${shortId(r.transactionId)} rejected — reserved funds released to the customer.`);
      } else {
        const e = await api.exportFraudCaseEvidence(pending.caseId);
        setNote(`Evidence pack exported (checksum ${e.checksum.slice(0, 23)}…) — see the Evidence page.`);
      }
      load();
      setPending(null);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const modalCopy: Record<string, { title: string; body: string; word: string; label: string; danger: boolean }> = {
    approve: {
      title: "Approve held transfer",
      body: "This releases the hold and posts the reserved funds to the ledger. The decision is recorded in the audit trail and is dual-approval-eligible.",
      word: "APPROVE",
      label: "Approve transfer",
      danger: false,
    },
    reject: {
      title: "Reject held transfer",
      body: "This rejects the transfer and releases the reserved funds back to the customer. The decision is recorded in the audit trail.",
      word: "REJECT",
      label: "Reject transfer",
      danger: true,
    },
    export: {
      title: "Export evidence pack",
      body: "Generates a checksummed evidence pack for this case and writes an audit log entry. Evidence exports may be subject to retention and legal hold.",
      word: "EXPORT",
      label: "Generate pack",
      danger: false,
    },
  };
  const copy = pending ? modalCopy[pending.kind] : null;

  return (
    <Shell active="/fraud-cases">
      <header className="topbar">
        <div>
          <p className="eyebrow">Fraud Operations</p>
          <h1>Case queue</h1>
          <p className="sub">Priority-sorted: severity, then risk. Approve posts the ledger; reject releases the hold.</p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {note && <p className="ok">{note}</p>}

      <section className="panel">
        <table>
          <thead>
            <tr>
              <th>Severity</th>
              <th>Risk</th>
              <th>Case</th>
              <th>Transaction</th>
              <th>Status</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {cases === null && <SkeletonRows cols={6} />}
            {sorted.map((c) => (
              <tr key={c.id}>
                <td><SeverityPill value={c.severity} /></td>
                <td><RiskBadge score={c.riskScore} /></td>
                <td className="mono">{shortId(c.id)}</td>
                <td className="mono">{shortId(c.transactionId)}</td>
                <td><StatusPill value={c.status} /></td>
                <td>
                  <div className="row" style={{ gap: 8 }}>
                    {c.status === "OPEN" && (
                      <>
                        <button onClick={() => setPending({ kind: "approve", caseId: c.id })}>Approve</button>
                        <button className="danger" onClick={() => setPending({ kind: "reject", caseId: c.id })}>
                          Reject
                        </button>
                      </>
                    )}
                    <button className="secondary" onClick={() => setPending({ kind: "export", caseId: c.id })}>
                      Export evidence
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {cases !== null && sorted.length === 0 && (
          <EmptyState
            title="No fraud cases"
            hint="Held high-risk transfers open cases automatically. Create a transfer well above the usual amount to a new beneficiary to test the flow."
          />
        )}
      </section>

      {pending && copy && (
        <ConfirmModal
          open
          title={copy.title}
          body={copy.body}
          confirmWord={copy.word}
          confirmLabel={copy.label}
          danger={copy.danger}
          busy={busy}
          onConfirm={runPending}
          onCancel={() => setPending(null)}
        />
      )}
    </Shell>
  );
}
