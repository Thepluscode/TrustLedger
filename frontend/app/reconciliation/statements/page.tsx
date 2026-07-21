"use client";

import { useEffect, useState } from "react";
import { EmptyState, SkeletonRows } from "../../components/ui";
import Shell from "../../components/Shell";
import { api } from "../../lib/api";
import { dateTime } from "../../lib/format";
import type { SettlementIngestResult, SettlementStatement } from "../../lib/types";

const SAMPLE_LINES = `[
  { "providerReference": "psk_ref_001", "amount": "100.0000", "fee": "1.50", "status": "SETTLED" }
]`;

export default function SettlementStatementsPage() {
  const [statements, setStatements] = useState<SettlementStatement[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SettlementIngestResult | null>(null);

  const [provider, setProvider] = useState("");
  const [currency, setCurrency] = useState("NGN");
  const [statementRef, setStatementRef] = useState("");
  const [periodStart, setPeriodStart] = useState("");
  const [periodEnd, setPeriodEnd] = useState("");
  const [linesJson, setLinesJson] = useState(SAMPLE_LINES);

  function load() {
    api.listSettlementStatements().then(setStatements).catch((e) => setError((e as Error).message));
  }
  useEffect(load, []);

  async function ingest() {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const lines = JSON.parse(linesJson);
      if (!Array.isArray(lines)) throw new Error("lines must be a JSON array");
      const r = await api.ingestSettlementStatement({
        provider,
        currency,
        statementRef,
        periodStart: new Date(periodStart).toISOString(),
        periodEnd: new Date(periodEnd).toISOString(),
        lines,
      });
      setResult(r);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const canIngest = provider && currency && statementRef && periodStart && periodEnd && !busy;

  return (
    <Shell active="/reconciliation/statements">
      <header className="topbar">
        <div>
          <p className="eyebrow">Money</p>
          <h1>Settlement statements</h1>
          <p className="sub">
            Ingest a provider settlement statement to reconcile it against our payment attempts. Breaks —
            unmatched lines, amount mismatches, or settlements missing from the statement — are raised as
            reconciliation issues.
          </p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}

      <section className="panel">
        <div className="panelHeader">
          <div>
            <h2>Ingest a statement</h2>
            <p className="sub">Normalized JSON. Re-ingesting the same reference is idempotent.</p>
          </div>
        </div>
        <div className="panelBody">
          <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: 10 }}>
            <label>Provider<input value={provider} onChange={(e) => setProvider(e.target.value)} placeholder="PAYSTACK" /></label>
            <label>Currency<input value={currency} onChange={(e) => setCurrency(e.target.value)} placeholder="NGN" /></label>
            <label>Statement ref<input value={statementRef} onChange={(e) => setStatementRef(e.target.value)} placeholder="STMT-2026-07" /></label>
            <label>Period start<input type="datetime-local" value={periodStart} onChange={(e) => setPeriodStart(e.target.value)} /></label>
            <label>Period end<input type="datetime-local" value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)} /></label>
          </div>
          <label style={{ display: "block", marginTop: 10 }}>
            Lines (JSON)
            <textarea value={linesJson} onChange={(e) => setLinesJson(e.target.value)} rows={6}
              className="mono" style={{ width: "100%", fontSize: 12 }} />
          </label>
          <div className="row" style={{ marginTop: 12 }}>
            <button onClick={ingest} disabled={!canIngest}>{busy ? "Ingesting…" : "Ingest statement"}</button>
          </div>

          {result && (
            <div className="notice ok" style={{ marginTop: 14 }}>
              <b>{result.alreadyIngested ? "Already ingested" : "Ingested"}</b> — {result.matched} matched,{" "}
              {result.unmatched} unmatched, {result.amountMismatch} amount mismatch, {result.missing} missing.
              {(result.unmatched > 0 || result.amountMismatch > 0 || result.missing > 0) && (
                <> Breaks are in <a href="/reconciliation">Reconciliation</a>.</>
              )}
            </div>
          )}
        </div>
      </section>

      <section className="panel" style={{ marginTop: 18 }}>
        <div className="panelHeader"><div><h2>Ingested statements</h2></div></div>
        <table>
          <thead>
            <tr><th>Statement</th><th>Provider</th><th>Currency</th><th>Lines</th><th>Total</th><th>Fees</th><th>Ingested</th></tr>
          </thead>
          <tbody>
            {statements === null && <SkeletonRows cols={7} />}
            {statements?.map((s) => (
              <tr key={s.id}>
                <td className="mono">{s.statementRef}</td>
                <td>{s.provider}</td>
                <td className="muted">{s.currency}</td>
                <td>{s.lineCount}</td>
                <td className="mono">{s.totalAmount}</td>
                <td className="mono muted">{s.totalFees}</td>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(s.ingestedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {statements !== null && statements.length === 0 && (
          <EmptyState title="No statements ingested" hint="Ingest a provider settlement statement above to reconcile it against your payment attempts." />
        )}
      </section>
    </Shell>
  );
}
