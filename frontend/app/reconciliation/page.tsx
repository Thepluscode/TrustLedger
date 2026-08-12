"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { EmptyState, SeverityPill, SkeletonRows, StatusPill } from "../components/ui";
import Shell from "../components/Shell";
import { api } from "../lib/api";
import { dateTime, shortId } from "../lib/format";
import type { ReconciliationIssueList } from "../lib/types";

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
  const cards: { label: string; value: number; alert?: boolean }[] = s
    ? [
        { label: "Open issues", value: s.open, alert: s.open > 0 },
        { label: "Critical (open)", value: s.criticalOpen, alert: s.criticalOpen > 0 },
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

      <section className="grid metrics">
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
        <table>
          <thead>
            <tr><th>Severity</th><th>Type</th><th>Affected entity</th><th>Status</th><th>Created</th></tr>
          </thead>
          <tbody>
            {items === null && <SkeletonRows cols={5} />}
            {items?.map((i) => (
              <tr key={i.id}>
                <td><SeverityPill value={i.severity} /></td>
                <td><Link href={`/reconciliation/${i.id}`}>{i.type.replace(/_/g, " ").toLowerCase()}</Link></td>
                <td className="muted"><span className="mono">{shortId(i.entityId)}</span> {i.entityType.replace(/_/g, " ").toLowerCase()}</td>
                <td><StatusPill value={i.status} /></td>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(i.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {items !== null && items.length === 0 && (
          <EmptyState
            title={status || severity ? "No issues match this filter" : "No reconciliation issues"}
            hint={status || severity
              ? "Try clearing the status or severity filter."
              : "Clean books. Mismatches (unbalanced ledger, pending-unknown payments, expired reservations, stuck outbox) would appear here."}
          />
        )}
      </section>
    </Shell>
  );
}
