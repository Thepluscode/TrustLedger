"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { EmptyState, SkeletonRows, StatusPill } from "../../../components/ui";
import Shell from "../../../components/Shell";
import { api } from "../../../lib/api";
import { dateTime, shortId } from "../../../lib/format";
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

  return (
    <Shell active="/reconciliation/statements">
      <header className="topbar">
        <div>
          <p className="eyebrow"><Link href="/reconciliation/statements">Settlement statements</Link> / statement</p>
          <h1>{s ? s.statementRef : "Statement"}</h1>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {!detail && !error && <div className="skeleton" style={{ maxWidth: 480, minHeight: 24 }} />}

      {s && (
        <>
          <section className="panel">
            <div className="panelBody">
              <div style={{ maxWidth: 560 }}>
                <div className="entry"><span className="muted">Provider</span><span>{s.provider}</span></div>
                <div className="entry"><span className="muted">Currency</span><span>{s.currency}</span></div>
                <div className="entry"><span className="muted">Period</span><span>{dateTime(s.periodStart)} — {dateTime(s.periodEnd)}</span></div>
                <div className="entry"><span className="muted">Lines</span><span>{s.lineCount}</span></div>
                <div className="entry"><span className="muted">Total amount</span><span className="mono">{s.totalAmount}</span></div>
                <div className="entry"><span className="muted">Total fees</span><span className="mono">{s.totalFees}</span></div>
                <div className="entry"><span className="muted">Ingested</span><span>{dateTime(s.ingestedAt)}</span></div>
              </div>
            </div>
          </section>

          <section className="panel" style={{ marginTop: 18 }}>
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
                  <tr key={i}>
                    <td className="mono">{l.providerReference}</td>
                    <td className="mono">{l.amount}</td>
                    <td className="mono muted">{l.fee}</td>
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
        </>
      )}
    </Shell>
  );
}
