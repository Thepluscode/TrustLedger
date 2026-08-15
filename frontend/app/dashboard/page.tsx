"use client";

import Link from "next/link";
import { useEffect, useState, type ReactNode } from "react";
import Shell from "../components/Shell";
import { EmptyState, RiskBadge, SeverityPill, SkeletonRows, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import { shortId } from "../lib/format";
import type { DashboardSummary, FraudCaseView } from "../lib/types";

const SEV_ORDER: Record<string, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };

type MetricIcon = "accounts" | "complete" | "review" | "rejected" | "shield" | "reconcile";

const METRIC_ICONS: Record<MetricIcon, ReactNode> = {
  accounts: <><path d="M3 9h18"/><path d="M5 9V6l7-3 7 3v3"/><path d="M6 9v8M10 9v8M14 9v8M18 9v8"/><path d="M3 21h18M4 17h16"/></>,
  complete: <><circle cx="12" cy="12" r="9"/><path d="m8 12 2.5 2.5L16 9"/></>,
  review: <><path d="M12 3 2.8 20h18.4L12 3Z"/><path d="M12 9v4M12 17h.01"/></>,
  rejected: <><circle cx="12" cy="12" r="9"/><path d="m9 9 6 6M15 9l-6 6"/></>,
  shield: <><path d="M12 3 20 6v5c0 5-3.4 8.7-8 10-4.6-1.3-8-5-8-10V6l8-3Z"/><path d="M9 12h6"/></>,
  reconcile: <><path d="M20 7h-7a4 4 0 0 0-4 4v1"/><path d="m17 4 3 3-3 3"/><path d="M4 17h7a4 4 0 0 0 4-4v-1"/><path d="m7 20-3-3 3-3"/></>,
};

function MetricGlyph({ name }: { name: MetricIcon }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      {METRIC_ICONS[name]}
    </svg>
  );
}

export default function DashboardPage() {
  const [s, setS] = useState<DashboardSummary | null>(null);
  const [cases, setCases] = useState<FraudCaseView[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.dashboard().then(setS).catch((e) => setError((e as Error).message));
    api.listFraudCases().then(setCases).catch(() => setCases([]));
  }, []);

  const queue = (cases ?? [])
    .filter((c) => c.status === "OPEN")
    .sort((a, b) => (SEV_ORDER[a.severity] ?? 9) - (SEV_ORDER[b.severity] ?? 9) || b.riskScore - a.riskScore)
    .slice(0, 6);

  const metrics: { label: string; value: number; alert?: boolean; href?: string; icon: MetricIcon; context: string }[] = s
    ? [
        { label: "Accounts", value: s.accounts, href: "/accounts", icon: "accounts", context: "Tenant-scoped financial accounts" },
        { label: "Transfers completed", value: s.transfersCompleted, icon: "complete", context: "Authoritatively completed" },
        { label: "Held for review", value: s.transfersHeld, alert: s.transfersHeld > 0, href: "/fraud-cases", icon: "review", context: "Awaiting an operator decision" },
        { label: "Transfers rejected", value: s.transfersRejected, icon: "rejected", context: "Stopped before settlement" },
        { label: "Open fraud cases", value: s.fraudCasesOpen, alert: s.fraudCasesOpen > 0, href: "/fraud-cases", icon: "shield", context: "Priority investigation queue" },
        { label: "Reconciliation issues", value: s.reconciliationIssuesOpen, alert: s.reconciliationIssuesOpen > 0, href: "/reconciliation", icon: "reconcile", context: "Provider and ledger exceptions" },
      ]
    : [];

  return (
    <Shell active="/dashboard">
      <section className="dashboard-hero premise-hero" aria-labelledby="dashboard-title">
        <div className="hero-copy">
          <p className="eyebrow">Financial operations cockpit</p>
          <h1 id="dashboard-title">Money movement, risk and evidence—under control.</h1>
          <p className="sub">
            A live operating view of tenant balances, transfer outcomes, fraud decisions and reconciliation exceptions.
            Every action remains ledger-backed, permissioned and auditable.
          </p>
        </div>
      </section>

      {error && <p className="error">{error}</p>}

      <section className="grid metrics" aria-label="Key metrics">
        {metrics.length === 0 && !error
          ? Array.from({ length: 6 }, (_, i) => (
              <article className="card metric-card" key={i}>
                <div className="skeleton" style={{ width: "62%" }} />
                <div className="skeleton" style={{ width: "36%", minHeight: 32 }} />
                <div className="skeleton" style={{ width: "78%" }} />
              </article>
            ))
          : metrics.map((m) => (
              <article className={`card metric-card${m.alert ? " alert" : ""}`} key={m.label}>
                <div className="metric-top">
                  <span>{m.label}</span>
                  <span className="metric-icon"><MetricGlyph name={m.icon} /></span>
                </div>
                <strong>{m.value}</strong>
                <div className="metric-foot">
                  <small>{m.context}</small>
                  {m.href && <Link href={m.href} aria-label={`View ${m.label}`}>View →</Link>}
                </div>
              </article>
            ))}
      </section>

      <section className="overview-layout">
        <section className="panel priority-panel" aria-label="High-risk queue">
          <div className="panelHeader">
            <div>
              <p className="eyebrow">Priority work</p>
              <h2>High-risk queue</h2>
              <p className="sub">Open fraud cases sorted by severity, then by risk score.</p>
            </div>
            <Link href="/fraud-cases">All cases →</Link>
          </div>
          <div style={{ overflowX: "auto" }}>
            <table className="desktop-table">
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
                {cases === null && <SkeletonRows cols={5} rows={4} />}
                {cases !== null && queue.map((c) => (
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
          </div>
          <div className="mobile-case-list">
            {queue.map((c) => (
              <article className="mobile-case" key={c.id}>
                <div><SeverityPill value={c.severity} /><RiskBadge score={c.riskScore} /></div>
                <strong>Transfer {shortId(c.transactionId)}</strong>
                <small>Case <span className="mono">{shortId(c.id)}</span></small>
                <Link href="/fraud-cases">Review case →</Link>
              </article>
            ))}
          </div>
          {cases !== null && queue.length === 0 && (
            <EmptyState title="No open fraud cases" hint="The current tenant has no fraud cases awaiting operator review." />
          )}
        </section>

        <aside className="operations-pulse" aria-label="Operations pulse">
          <section className="panel insight-card">
            <p className="eyebrow">Operations pulse</p>
            <h2>What needs attention</h2>
            <div className="pulse-list">
              <div className="pulse-row">
                <span className="pulse-label"><span>Fraud reviews</span><small>Open cases</small></span>
                <strong className="pulse-value">{s?.fraudCasesOpen ?? "—"}</strong>
              </div>
              <div className="pulse-row">
                <span className="pulse-label"><span>Held transfers</span><small>Awaiting decision</small></span>
                <strong className="pulse-value">{s?.transfersHeld ?? "—"}</strong>
              </div>
              <div className="pulse-row">
                <span className="pulse-label"><span>Recon exceptions</span><small>Open issues</small></span>
                <strong className="pulse-value">{s?.reconciliationIssuesOpen ?? "—"}</strong>
              </div>
            </div>
          </section>

          <section className="control-note">
            <p className="eyebrow">Control principle</p>
            <strong>Financial truth stays authoritative.</strong>
            <p>Provider responses, webhooks and operator actions can advance state only through controlled, auditable transitions.</p>
          </section>
        </aside>
      </section>
      <Link href="/transfers/new" className="mobile-primary-action">＋ Create transfer</Link>
    </Shell>
  );
}
