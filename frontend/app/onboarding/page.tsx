"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { api } from "../lib/api";

type Step = { title: string; detail: string; done: boolean; href: string; cta: string; core: boolean };

export default function OnboardingPage() {
  const [steps, setSteps] = useState<Step[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      api.listSettlementStatements().catch(() => []),
      api.listReconciliationIssues().catch(() => null),
      api.listAuditLogs().catch(() => []),
      api.listEvidence().catch(() => []),
      api.listProviderConfigs().catch(() => []),
    ])
      .then(([statements, issues, auditLogs, evidence, providers]) => {
        const hasReconciliationResult = statements.length > 0 || (issues?.summary.total ?? 0) > 0;
        setSteps([
          { title: "Organisation created", detail: "Your tenant-scoped reliability workspace is isolated.", done: true, href: "/dashboard", cta: "Open dashboard", core: true },
          { title: "Ingest settlement evidence", detail: "Import a provider statement without changing its original source state.", done: statements.length > 0, href: "/reconciliation/statements", cta: "Import statement", core: true },
          { title: "Review reconciliation results", detail: "Matched records and unexplained breaks remain visible for investigation.", done: hasReconciliationResult, href: "/reconciliation", cta: "Review results", core: true },
          { title: "Inspect attributable history", detail: "Confirm sensitive activity can be traced to an actor and time.", done: auditLogs.length > 0, href: "/audit-logs", cta: "Open audit logs", core: true },
          { title: "Connect a read-only data source", detail: "Optional after approval; exported files remain the starting path.", done: providers.length > 0, href: "/admin", cta: "Review connections", core: false },
          { title: "Review evidence exports", detail: "Checksummed exports support investigation and audit hand-off.", done: evidence.length > 0, href: "/evidence", cta: "Open evidence", core: false },
        ]);
      })
      .catch((e) => setError((e as Error).message));
  }, []);

  const coreDone = (steps ?? []).filter((s) => s.core && s.done).length;
  const coreTotal = (steps ?? []).filter((s) => s.core).length;
  const allDone = steps !== null && steps.every((s) => s.done);

  return (
    <Shell active="/onboarding">
      <header className="topbar">
        <div>
          <p className="eyebrow">Getting started</p>
          <h1>Onboarding</h1>
          <p className="sub">Set up the read-only reliability workflow. Every item checks itself from tenant data.</p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}

      <div className="onboarding-workspace">
      <aside className="onboarding-progress">
        <p className="eyebrow">Core setup</p>
        <strong>{coreDone} of {coreTotal}</strong>
        <div className="progress-track"><span style={{ width: `${coreTotal ? (coreDone / coreTotal) * 100 : 0}%` }} /></div>
        <p>{coreDone === coreTotal ? "Core reliability setup is complete." : "Complete the evidence-to-exception path before reviewing optional controls."}</p>
        <small>Completion reflects current tenant records, not pilot or production readiness.</small>
      </aside>

      <section className="panel onboarding-checklist">
        <div className="panelHeader"><div><h2>Reliability workflow checklist</h2><p className="sub">Source evidence first; exceptions and auditability follow.</p></div></div>
        {steps === null ? (
          <div className="panelBody"><div className="skeleton" style={{ minHeight: 60 }} /></div>
        ) : (
          steps.map((s, i) => (
            <div key={s.title} className={`setup-step${s.done ? " complete" : ""}`}>
              <div className="setup-step-copy">
                <span className="setup-marker">{s.done ? "✓" : i + 1}</span>
                <div>
                  <div>{s.title}{!s.core && <span> · supporting control</span>}</div>
                  <p>{s.detail}</p>
                </div>
              </div>
              <Link href={s.href} className={`btn${s.done ? " secondary" : ""}`} style={{ textDecoration: "none", whiteSpace: "nowrap" }}>
                {s.done ? "Review" : s.cta}
              </Link>
            </div>
          ))
        )}
      </section>
      </div>

      {steps && allDone && <div className="notice ok onboarding-complete">All configured read-only workflow steps are present in this tenant.</div>}
    </Shell>
  );
}
