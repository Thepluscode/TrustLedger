"use client";

import { Fragment, useEffect, useState } from "react";
import Shell from "../components/Shell";
import { ConfirmModal, EmptyState, RiskBadge, SeverityPill, SkeletonRows, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import { shortId } from "../lib/format";
import type { FraudCaseView, FraudSignalDetail, FraudSignalFrequency } from "../lib/types";

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
  const [summary, setSummary] = useState<FraudSignalFrequency[] | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [caseSignals, setCaseSignals] = useState<FraudSignalDetail[] | null>(null);

  function load() {
    api.listFraudCases().then(setCases).catch((e) => setError((e as Error).message));
    api.fraudSignalSummary().then(setSummary).catch(() => setSummary([]));
  }
  useEffect(load, []);

  function toggleSignals(caseId: string) {
    if (expanded === caseId) {
      setExpanded(null);
      return;
    }
    setExpanded(caseId);
    setCaseSignals(null);
    api.fraudCaseSignals(caseId).then(setCaseSignals).catch(() => setCaseSignals([]));
  }

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

      {summary && summary.length > 0 && (
        <section className="panel">
          <div className="panelHeader">
            <div>
              <h2>Signal frequency</h2>
              <p className="sub">Which fraud signals fire most across your tenant — the control graph as insight. Tune thresholds against what actually drives holds.</p>
            </div>
          </div>
          <table>
            <thead>
              <tr><th>Signal</th><th>Times fired</th><th>Total score contribution</th></tr>
            </thead>
            <tbody>
              {summary.map((f) => (
                <tr key={f.signalType}>
                  <td className="mono">{f.signalType.replace(/_/g, " ").toLowerCase()}</td>
                  <td>{f.occurrences}</td>
                  <td className="muted">{f.totalScoreDelta}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      <section className="panel" style={{ marginTop: 18 }}>
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
              <Fragment key={c.id}>
                <tr>
                  <td><SeverityPill value={c.severity} /></td>
                  <td><RiskBadge score={c.riskScore} /></td>
                  <td className="mono">{shortId(c.id)}</td>
                  <td className="mono">{shortId(c.transactionId)}</td>
                  <td><StatusPill value={c.status} /></td>
                  <td>
                    <div className="row" style={{ gap: 8 }}>
                      <button className="secondary" onClick={() => toggleSignals(c.id)}>
                        {expanded === c.id ? "Hide why" : "Why?"}
                      </button>
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
                {expanded === c.id && (
                  <tr>
                    <td colSpan={6} style={{ background: "var(--surface-2, rgba(0,0,0,0.03))" }}>
                      {caseSignals === null && <span className="muted">Loading signals…</span>}
                      {caseSignals?.length === 0 && <span className="muted">No recorded signals for this case.</span>}
                      {caseSignals?.map((s, i) => (
                        <div key={i} className="entry" style={{ alignItems: "flex-start", flexDirection: "column", gap: 2 }}>
                          <span>
                            <b>{s.signalType.replace(/_/g, " ").toLowerCase()}</b>{" "}
                            <span className="muted">+{s.scoreDelta} · {s.severity.toLowerCase()}</span>
                          </span>
                          <span className="muted">{s.reason}</span>
                        </div>
                      ))}
                    </td>
                  </tr>
                )}
              </Fragment>
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
