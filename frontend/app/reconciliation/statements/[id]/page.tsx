"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { EmptyState, SkeletonRows, StatusPill } from "../../../components/ui";
import Shell from "../../../components/Shell";
import { api } from "../../../lib/api";
import { dateTime, money, shortId } from "../../../lib/format";
import type { SettlementStatementDetail } from "../../../lib/types";

export default function SettlementStatementDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const [detail, setDetail] = useState<SettlementStatementDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (id) api.getSettlementStatement(id).then(setDetail).catch((e) => setError((e as Error).message));
  }, [id]);

  const s = detail?.statement;
  const lines = detail?.lines ?? null;
  const matched = lines?.filter((line) => line.matchStatus === "MATCHED").length ?? 0;
  const breaks = lines?.filter((line) => line.matchStatus !== "MATCHED").length ?? 0;
  const netAmount = s ? Number(s.totalAmount) - Number(s.totalFees) : 0;

  return (
    <Shell active="/reconciliation/statements">
      <header className="topbar">
        <div>
          <p className="eyebrow"><Link href="/reconciliation/statements">Settlement statements</Link> / statement</p>
          <div className="row statement-title-row">
            <h1>{s ? s.statementRef : "Statement"}</h1>
            {s && <StatusPill value={breaks > 0 ? "BREAKS_FOUND" : "RECONCILED"} />}
          </div>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {!detail && !error && <div className="skeleton" style={{ maxWidth: 480, minHeight: 24 }} />}

      {s && (
        <>
          <section className="grid statement-detail-metrics" aria-label="Statement reconciliation summary">
            <article className="card"><span>Total lines</span><strong>{s.lineCount}</strong><small>Imported records</small></article>
            <article className="card"><span>Matched</span><strong>{matched}</strong><small>Provider and ledger agree</small></article>
            <article className="card"><span>Breaks</span><strong>{breaks}</strong><small>Require investigation</small></article>
            <article className="card"><span>Gross amount</span><strong>{money(s.totalAmount, s.currency)}</strong><small>{s.currency}</small></article>
            <article className="card"><span>Fees</span><strong>{money(s.totalFees, s.currency)}</strong><small>Provider fees</small></article>
            <article className="card"><span>Net amount</span><strong>{money(netAmount, s.currency)}</strong><small>Gross less fees</small></article>
          </section>

          <div className="statement-detail-layout">
            <section className="panel statement-lines">
              <div className="panelHeader">
                <div><h2>Lines</h2><p className="sub">Each line matched against our payment attempts. Breaks are raised as reconciliation issues.</p></div>
              </div>
              <table>
                <thead>
                  <tr><th>Provider reference</th><th>Amount</th><th>Fee</th><th>Provider status</th><th>Match</th><th>Matched attempt</th></tr>
                </thead>
                <tbody>
                  {lines === null && <SkeletonRows cols={6} />}
                  {lines?.map((l, i) => (
                    <tr key={i} className={l.matchStatus !== "MATCHED" ? "statement-break-row" : ""}>
                      <td className="mono">{l.providerReference}</td>
                      <td className="mono">{money(l.amount, s.currency)}</td>
                      <td className="mono muted">{money(l.fee, s.currency)}</td>
                      <td className="muted">{l.status}</td>
                      <td><StatusPill value={l.matchStatus} /></td>
                      <td className="mono muted">{l.matchedAttemptId ? shortId(l.matchedAttemptId) : "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {lines !== null && lines.length === 0 && (
                <EmptyState title="No lines" hint="This statement has no lines." />
              )}
            </section>

            <aside className="statement-detail-sidebar">
              <section className="panel">
                <div className="panelHeader"><h2>Statement details</h2></div>
                <div className="panelBody">
                  <div className="entry"><span className="muted">Provider</span><span>{s.provider}</span></div>
                  <div className="entry"><span className="muted">Currency</span><span>{s.currency}</span></div>
                  <div className="entry"><span className="muted">Period</span><span>{dateTime(s.periodStart)} — {dateTime(s.periodEnd)}</span></div>
                  <div className="entry"><span className="muted">Ingested</span><span>{dateTime(s.ingestedAt)}</span></div>
                </div>
              </section>
              <section className={`notice ${breaks > 0 ? "warn" : "ok"}`}>
                <b>{breaks > 0 ? `${breaks} reconciliation break${breaks === 1 ? "" : "s"}` : "Statement reconciled"}</b>
                <span>{breaks > 0 ? " Open Reconciliation to inspect evidence and resolve each discrepancy." : " All imported lines match a recorded payment attempt."}</span>
              </section>
            </aside>
          </div>
        </>
      )}
    </Shell>
  );
}
