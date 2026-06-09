"use client";

import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { api } from "../lib/api";

const PLANS = ["FREE_SANDBOX", "PILOT", "PROFESSIONAL", "ENTERPRISE", "INTERNAL"];

export default function AdminPage() {
  const [transfers, setTransfers] = useState<number | null>(null);
  const [quota, setQuota] = useState<Record<string, number> | null>(null);
  const [events, setEvents] = useState<string[]>([]);
  const [configs, setConfigs] = useState<{ provider: string; environment: string; enabled: boolean }[]>([]);
  const [plan, setPlan] = useState("PILOT");
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  function load() {
    api.getUsage("transfers_created").then((u) => setTransfers(u.currentMonth)).catch(() => {});
    api.getTenantQuota().then(setQuota).catch((e) => setError((e as Error).message));
    api.getBillingEvents().then(setEvents).catch(() => {});
    api.listProviderConfigs().then(setConfigs).catch(() => {});
  }
  useEffect(load, []);

  async function applyPlan() {
    setNote(null);
    setError(null);
    try {
      const r = await api.changePlan(plan);
      setNote(`Plan changed to ${r.plan} — billing event emitted.`);
      load();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <Shell active="/admin">
      <header className="topbar">
        <div>
          <p className="eyebrow">Enterprise Admin</p>
          <h1>Tenant operations</h1>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {note && <p className="ok">{note}</p>}

      <section className="panel" style={{ padding: 16 }}>
        <h2>Usage (this month)</h2>
        <p>Transfers created: <strong>{transfers ?? "—"}</strong></p>
      </section>

      <section className="panel" style={{ padding: 16 }}>
        <h2>Plan</h2>
        <div className="row">
          <select value={plan} onChange={(e) => setPlan(e.target.value)}>
            {PLANS.map((p) => <option key={p} value={p}>{p}</option>)}
          </select>
          <button onClick={applyPlan}>Change plan</button>
        </div>
        <p className="muted">Recent billing events: {events.length ? events.slice(0, 6).join(", ") : "none"}</p>
      </section>

      <section className="panel" style={{ padding: 16 }}>
        <h2>Quotas</h2>
        {quota ? (
          <ul>
            {Object.entries(quota).map(([k, v]) => <li key={k}>{k}: <strong>{v}</strong></li>)}
          </ul>
        ) : <p className="muted">—</p>}
      </section>

      <section className="panel" style={{ padding: 16 }}>
        <h2>Provider configs</h2>
        {configs.length ? (
          <table>
            <thead><tr><th>Provider</th><th>Environment</th><th>Enabled</th></tr></thead>
            <tbody>
              {configs.map((c, i) => (
                <tr key={i}><td>{c.provider}</td><td>{c.environment}</td><td>{c.enabled ? "yes" : "no (disabled)"}</td></tr>
              ))}
            </tbody>
          </table>
        ) : <p className="muted">No provider configs yet.</p>}
      </section>
    </Shell>
  );
}
