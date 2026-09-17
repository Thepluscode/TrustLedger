"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { EmptyState, SkeletonRows, StatusPill } from "../../components/ui";
import Shell from "../../components/Shell";
import { api } from "../../lib/api";
import { dateTime } from "../../lib/format";
import type { ReconCase } from "../../lib/types";

export default function ReconCasesPage() {
  const [cases, setCases] = useState<ReconCase[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ caseRef: "", title: "", periodStart: "", periodEnd: "", settlementSlaDays: "2" });

  function load() {
    api.listReconCases().then(setCases).catch((e) => setError((e as Error).message));
  }
  useEffect(load, []);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const created = await api.createReconCase({
        caseRef: form.caseRef.trim(),
        title: form.title.trim(),
        periodStart: `${form.periodStart}T00:00:00Z`,
        periodEnd: `${form.periodEnd}T00:00:00Z`,
        settlementSlaDays: Number(form.settlementSlaDays),
      });
      window.location.href = `/reconciliation/cases/${created.reconciliationCase.id}`;
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  const ready = form.caseRef.trim() && form.title.trim() && form.periodStart && form.periodEnd;

  return (
    <Shell active="/reconciliation/cases">
      <header className="topbar">
        <div>
          <p className="eyebrow"><Link href="/reconciliation">Reconciliation</Link> / cases</p>
          <h1>Reconciliation cases</h1>
          <p className="sub">One case per period: import the ledger, provider and settlement files, reconcile them, work the exceptions, export the evidence.</p>
        </div>
      </header>
      {error && <p className="error" role="alert">{error}</p>}

      <section className="panel">
        <div className="panelHeader"><div><h2>New case</h2><p className="sub">Read-only: a case never moves or routes money.</p></div></div>
        <form className="panelBody" onSubmit={create}>
          <div className="row" style={{ gap: 12, flexWrap: "wrap", alignItems: "flex-end" }}>
            <label>Case reference<br /><input value={form.caseRef} onChange={(e) => setForm({ ...form, caseRef: e.target.value })} placeholder="ACME-2026-08" pattern="[A-Za-z0-9._\-]+" required /></label>
            <label style={{ flex: 1, minWidth: 220 }}>Title<br /><input style={{ width: "100%" }} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} placeholder="August 2026 cross-provider reconciliation" required /></label>
            <label>Period start<br /><input type="date" value={form.periodStart} onChange={(e) => setForm({ ...form, periodStart: e.target.value })} required /></label>
            <label>Period end (exclusive)<br /><input type="date" value={form.periodEnd} onChange={(e) => setForm({ ...form, periodEnd: e.target.value })} required /></label>
            <label>Settlement SLA (days)<br /><input type="number" min={0} max={60} style={{ width: 90 }} value={form.settlementSlaDays} onChange={(e) => setForm({ ...form, settlementSlaDays: e.target.value })} /></label>
            <button disabled={!ready || busy}>{busy ? "Creating…" : "Create case"}</button>
          </div>
        </form>
      </section>

      <section className="panel" style={{ marginTop: 18 }}>
        <div className="panelHeader"><div><h2>Cases</h2></div></div>
        <table>
          <thead><tr><th>Reference</th><th>Title</th><th>Period</th><th>Status</th><th>Created</th></tr></thead>
          <tbody>
            {cases === null && !error && <SkeletonRows cols={5} />}
            {cases?.map((c) => (
              <tr key={c.id}>
                <td><Link href={`/reconciliation/cases/${c.id}`} className="mono">{c.caseRef}</Link></td>
                <td>{c.title}</td>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{c.periodStart.slice(0, 10)} → {c.periodEnd.slice(0, 10)}</td>
                <td><StatusPill value={c.status} /></td>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(c.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {cases !== null && cases.length === 0 && (
          <EmptyState title="No cases yet" hint="Create one above, then import the internal ledger export and at least one provider file." />
        )}
      </section>
    </Shell>
  );
}
