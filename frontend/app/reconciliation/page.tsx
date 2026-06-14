"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { EmptyState, SeverityPill, SkeletonRows, StatusPill } from "../components/ui";
import Shell from "../components/Shell";
import { api } from "../lib/api";
import { dateTime, shortId } from "../lib/format";
import type { ReconciliationIssue } from "../lib/types";

export default function ReconciliationPage() {
  const [issues, setIssues] = useState<ReconciliationIssue[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.listReconciliationIssues().then(setIssues).catch((e) => setError((e as Error).message));
  }, []);

  const open = (issues ?? []).filter((i) => i.status === "OPEN");
  const cards: { label: string; value: number; alert?: boolean }[] = issues
    ? [
        { label: "Open issues", value: open.length, alert: open.length > 0 },
        { label: "Critical (open)", value: open.filter((i) => i.severity === "CRITICAL").length, alert: open.some((i) => i.severity === "CRITICAL") },
        { label: "Resolved", value: issues.filter((i) => i.status === "RESOLVED").length },
        { label: "Total", value: issues.length },
      ]
    : [];

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
        <table>
          <thead>
            <tr><th>Severity</th><th>Type</th><th>Affected entity</th><th>Status</th><th>Created</th></tr>
          </thead>
          <tbody>
            {issues === null && <SkeletonRows cols={5} />}
            {issues?.map((i) => (
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
        {issues !== null && issues.length === 0 && (
          <EmptyState
            title="No reconciliation issues"
            hint="Clean books. Mismatches (unbalanced ledger, pending-unknown payments, expired reservations, stuck outbox) would appear here."
          />
        )}
      </section>
    </Shell>
  );
}
