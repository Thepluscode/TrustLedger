"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { EmptyState, SeverityPill, SkeletonRows, StatusPill } from "../components/ui";
import Shell from "../components/Shell";
import { api } from "../lib/api";
import { dateTime, money, shortId } from "../lib/format";
import type { ReconciliationIssue, ReconciliationIssueList } from "../lib/types";

/** "—" when the break carries no monetary value: absent is not zero, and 0 would claim nothing is at risk. */
function exposure(issue: ReconciliationIssue): string {
  return issue.exposureAmount && issue.exposureCurrency
    ? money(issue.exposureAmount, issue.exposureCurrency)
    : "—";
}

/** A resolved case is never late, however long it sat open before someone closed it. */
function isOverdue(issue: ReconciliationIssue): boolean {
  return issue.status === "OPEN" && new Date(issue.dueAt).getTime() < Date.now();
}

export default function ReconciliationPage() {
  const [data, setData] = useState<ReconciliationIssueList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState("");
  const [severity, setSeverity] = useState("");

  useEffect(() => {
    setData(null);
    api.listReconciliationIssues(status, severity).then(setData).catch((e) => setError((e as Error).message));
  }, [status, severity]);

  const s = data?.summary;
  // Exposure gets one card per currency. Adding them together would be arithmetic on incomparable
  // units, and the resulting number is exactly the one someone would have acted on.
  const exposureCards = s
    ? Object.entries(s.openExposureByCurrency).map(([currency, amount]) => ({
        label: `At risk · ${currency}`,
        value: money(amount, currency),
        alert: true,
      }))
    : [];
  const cards: { label: string; value: string | number; alert?: boolean }[] = s
    ? [
        { label: "Open issues", value: s.open, alert: s.open > 0 },
        { label: "Critical (open)", value: s.criticalOpen, alert: s.criticalOpen > 0 },
        { label: "Past deadline", value: s.overdueOpen, alert: s.overdueOpen > 0 },
        ...exposureCards,
        { label: "Resolved", value: s.resolved },
        { label: "Total", value: s.total },
      ]
    : [];

  const items = data?.items ?? null;

  return (
    <Shell active="/reconciliation">
      <header className="topbar">
        <div>
          <p className="eyebrow">Operations</p>
          <h1>Reconciliation</h1>
          <p className="sub">Financial and operational mismatches the worker found — resolve them or trace the evidence.</p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}

      <section className="grid metrics reconciliation-metrics">
        {cards.length === 0 && !error
          ? Array.from({ length: 4 }, (_, i) => (
              <article className="card" key={i}><div className="skeleton" style={{ width: "55%" }} /><div className="skeleton" style={{ width: "30%", minHeight: 26 }} /></article>
            ))
          : cards.map((c) => (
              <article className={`card${c.alert ? " alert" : ""}`} key={c.label}>
                <span>{c.label}</span>
                <strong>{c.value}</strong>
              </article>
            ))}
      </section>

      <section className="panel">
        <div className="panelHeader">
          <div><h2>Issues</h2></div>
          <div className="row" style={{ gap: 8 }}>
            <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Filter by status">
              <option value="">All statuses</option>
              <option value="OPEN">Open</option>
              <option value="RESOLVED">Resolved</option>
            </select>
            <select value={severity} onChange={(e) => setSeverity(e.target.value)} aria-label="Filter by severity">
              <option value="">All severities</option>
              <option value="CRITICAL">Critical</option>
              <option value="HIGH">High</option>
              <option value="MEDIUM">Medium</option>
              <option value="LOW">Low</option>
            </select>
          </div>
        </div>
        <table className="desktop-table">
          <thead>
            <tr><th>Severity</th><th>Type</th><th>Affected entity</th><th>At risk</th><th>Due</th><th>Status</th><th>Created</th></tr>
          </thead>
          <tbody>
            {items === null && <SkeletonRows cols={7} />}
            {items?.map((i) => (
              <tr key={i.id}>
                <td><SeverityPill value={i.severity} /></td>
                <td><Link href={`/reconciliation/${i.id}`}>{i.type.replace(/_/g, " ").toLowerCase()}</Link></td>
                <td className="muted"><span className="mono">{shortId(i.entityId)}</span> {i.entityType.replace(/_/g, " ").toLowerCase()}</td>
                <td className="mono">{exposure(i)}</td>
                <td className={isOverdue(i) ? "error" : "muted"} style={{ whiteSpace: "nowrap" }}>
                  {dateTime(i.dueAt)}{isOverdue(i) ? " · overdue" : ""}
                </td>
                <td><StatusPill value={i.status} /></td>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(i.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="mobile-record-list reconciliation-record-list">
          {items?.map((issue) => (
            <article className={`mobile-record reconciliation-record ${issue.severity.toLowerCase()}`} key={issue.id}>
              <div className="record-head"><SeverityPill value={issue.severity} /><StatusPill value={issue.status} /></div>
              <div><small>Issue</small><h2>{issue.type.replace(/_/g, " ").toLowerCase()}</h2></div>
              <p className="muted"><span className="mono">{shortId(issue.entityId)}</span> · {issue.entityType.replace(/_/g, " ").toLowerCase()}</p>
              <p className={isOverdue(issue) ? "error" : "muted"}>
                {issue.exposureAmount && issue.exposureCurrency
                  ? <><span className="mono">{exposure(issue)}</span> at risk</>
                  : "No monetary amount"} · due {dateTime(issue.dueAt)}
                {isOverdue(issue) ? " · overdue" : ""}
              </p>
              <div className="record-foot"><small>Detected {dateTime(issue.createdAt)}</small><span className="mono">{shortId(issue.id)}</span></div>
              <Link href={`/reconciliation/${issue.id}`} className="record-action">Investigate →</Link>
            </article>
          ))}
        </div>
        {items !== null && items.length === 0 && (
          <EmptyState
            title={status || severity ? "No issues match this filter" : "No reconciliation issues"}
            hint={status || severity
              ? "Try clearing the status or severity filter."
              : "Clean books. Mismatches (unbalanced ledger, pending-unknown payments, expired reservations, stuck outbox) would appear here."}
          />
        )}
      </section>
      <div className="notice reconciliation-principle"><b>Why mismatches remain visible</b> — issues stay open until a permissioned operator explicitly resolves the underlying difference. This preserves a complete audit trail.</div>
    </Shell>
  );
}
