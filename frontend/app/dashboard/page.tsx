"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { EmptyState, RiskBadge, SeverityPill, SkeletonRows, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import { shortId } from "../lib/format";
import type { DashboardSummary, FraudCaseView } from "../lib/types";

const SEV_ORDER: Record<string, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };

export default function DashboardPage() {
  const [s, setS] = useState<DashboardSummary | null>(null);
  const [cases, setCases] = useState<FraudCaseView[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.dashboard().then(setS).catch((e) => setError((e as Error).message));
    api.listFraudCases().then(setCases).catch(() => setCases([]));
  }, []);

  // §10.2 priority sort: severity first, then risk score.
  const queue = (cases ?? [])
    .filter((c) => c.status === "OPEN")
    .sort((a, b) => (SEV_ORDER[a.severity] ?? 9) - (SEV_ORDER[b.severity] ?? 9) || b.riskScore - a.riskScore)
    .slice(0, 6);

  const metrics: { label: string; value: number; alert?: boolean; href?: string }[] = s
    ? [
        { label: "Accounts", value: s.accounts, href: "/accounts" },
        { label: "Transfers completed", value: s.transfersCompleted },
        { label: "Held for review", value: s.transfersHeld, alert: s.transfersHeld > 0, href: "/fraud-cases" },
        { label: "Transfers rejected", value: s.transfersRejected },
        { label: "Open fraud cases", value: s.fraudCasesOpen, alert: s.fraudCasesOpen > 0, href: "/fraud-cases" },
        { label: "Reconciliation issues", value: s.reconciliationIssuesOpen, alert: s.reconciliationIssuesOpen > 0 },
      ]
    : [];

  return (
    <Shell active="/dashboard">
      <header className="topbar">
        <div>
          <p className="eyebrow">Financial Operations Cockpit</p>
          <h1>Overview</h1>
          <p className="sub">What is happening now, what is risky, what money moved, what to do next.</p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}

      <section className="grid metrics" aria-label="Key metrics">
        {metrics.length === 0 && !error
          ? Array.from({ length: 6 }, (_, i) => (
              <article className="card" key={i}>
                <div className="skeleton" style={{ width: "60%" }} />
                <div className="skeleton" style={{ width: "35%", minHeight: 28 }} />
              </article>
            ))
          : metrics.map((m) => (
              <article className={`card${m.alert ? " alert" : ""}`} key={m.label}>
                <span>{m.label}</span>
                <strong>{m.value}</strong>
                {m.href && (
                  <Link href={m.href} style={{ fontSize: 12 }}>
                    View →
                  </Link>
                )}
              </article>
            ))}
      </section>

      <section className="panel" aria-label="High-risk queue">
        <div className="panelHeader">
          <div>
            <h2>High-risk queue</h2>
            <p className="sub">Open fraud cases, priority-sorted by severity then risk.</p>
          </div>
          <Link href="/fraud-cases">All cases →</Link>
        </div>
        <table>
          <thead>
            <tr>
              <th>Severity</th>
              <th>Risk</th>
              <th>Case</th>
              <th>Transaction</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {cases === null && <SkeletonRows cols={5} rows={3} />}
            {cases !== null &&
              queue.map((c) => (
                <tr key={c.id}>
                  <td><SeverityPill value={c.severity} /></td>
                  <td><RiskBadge score={c.riskScore} /></td>
                  <td className="mono">{shortId(c.id)}</td>
                  <td className="mono">{shortId(c.transactionId)}</td>
                  <td><StatusPill value={c.status} /></td>
                </tr>
              ))}
          </tbody>
        </table>
        {cases !== null && queue.length === 0 && (
          <EmptyState
            title="No open fraud cases"
            hint="Run a high-risk transfer (large amount to a new beneficiary from an unknown device) to test case creation."
          />
        )}
      </section>
    </Shell>
  );
}
