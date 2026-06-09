"use client";

import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { api } from "../lib/api";
import type { FraudCaseView } from "../lib/types";

export default function FraudCasesPage() {
  const [cases, setCases] = useState<FraudCaseView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  function load() {
    api.listFraudCases().then(setCases).catch((e) => setError((e as Error).message));
  }
  useEffect(load, []);

  const [note, setNote] = useState<string | null>(null);

  async function act(caseId: string, action: "approve" | "reject") {
    setBusy(caseId);
    setError(null);
    try {
      if (action === "approve") await api.approveCase(caseId);
      else await api.rejectCase(caseId);
      load();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function exportEvidence(caseId: string) {
    setError(null);
    setNote(null);
    try {
      const e = await api.exportFraudCaseEvidence(caseId);
      setNote(`Evidence exported (${e.checksum.slice(0, 23)}…). See the Evidence page.`);
    } catch (err) {
      setError((err as Error).message);
    }
  }

  return (
    <Shell active="/fraud-cases">
      <header className="topbar">
        <div>
          <p className="eyebrow">Fraud Cases</p>
          <h1>High-risk queue</h1>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {note && <p className="ok">{note}</p>}
      <section className="panel">
        <table>
          <thead>
            <tr><th>Case</th><th>Transaction</th><th>Risk</th><th>Severity</th><th>Status</th><th>Action</th></tr>
          </thead>
          <tbody>
            {cases.map((c) => (
              <tr key={c.id}>
                <td>{c.id.slice(0, 8)}…</td>
                <td>{c.transactionId.slice(0, 8)}…</td>
                <td><strong>{c.riskScore}</strong></td>
                <td>{c.severity}</td>
                <td><span className="badge">{c.status}</span></td>
                <td>
                  <div className="row">
                    {c.status === "OPEN" && (
                      <>
                        <button disabled={busy === c.id} onClick={() => act(c.id, "approve")}>Approve</button>
                        <button className="secondary" disabled={busy === c.id} onClick={() => act(c.id, "reject")}>Reject</button>
                      </>
                    )}
                    <button className="secondary" onClick={() => exportEvidence(c.id)}>Export evidence</button>
                  </div>
                </td>
              </tr>
            ))}
            {cases.length === 0 && (
              <tr><td colSpan={6} className="muted">No fraud cases.</td></tr>
            )}
          </tbody>
        </table>
      </section>
    </Shell>
  );
}
