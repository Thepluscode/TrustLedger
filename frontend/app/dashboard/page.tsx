"use client";

import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { api } from "../lib/api";
import type { DashboardSummary } from "../lib/types";

export default function DashboardPage() {
  const [s, setS] = useState<DashboardSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.dashboard().then(setS).catch((e) => setError((e as Error).message));
  }, []);

  const cards: [string, number][] = s
    ? [
        ["Accounts", s.accounts],
        ["Transfers completed", s.transfersCompleted],
        ["Held for review", s.transfersHeld],
        ["Transfers rejected", s.transfersRejected],
        ["Open fraud cases", s.fraudCasesOpen],
        ["Reconciliation issues", s.reconciliationIssuesOpen],
      ]
    : [];

  return (
    <Shell active="/dashboard">
      <header className="topbar">
        <div>
          <p className="eyebrow">Financial Operations Cockpit</p>
          <h1>Dashboard</h1>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      <section className="grid metrics">
        {cards.map(([label, value]) => (
          <article className="card" key={label}>
            <span>{label}</span>
            <strong>{value}</strong>
          </article>
        ))}
      </section>
    </Shell>
  );
}
