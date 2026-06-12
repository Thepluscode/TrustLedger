"use client";

import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { ConfirmModal, StatusPill } from "../components/ui";
import { api } from "../lib/api";

const PLANS = ["FREE_SANDBOX", "PILOT", "PROFESSIONAL", "ENTERPRISE", "INTERNAL"];

export default function AdminPage() {
  const [transfers, setTransfers] = useState<number | null>(null);
  const [quota, setQuota] = useState<Record<string, number> | null>(null);
  const [events, setEvents] = useState<string[]>([]);
  const [configs, setConfigs] = useState<{ provider: string; environment: string; enabled: boolean }[]>([]);
  const [plan, setPlan] = useState("PILOT");
  const [confirmPlan, setConfirmPlan] = useState(false);
  const [busy, setBusy] = useState(false);
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
    setBusy(true);
    setNote(null);
    setError(null);
    try {
      const r = await api.changePlan(plan);
      setNote(`Plan changed to ${r.plan} — billing event emitted and audited.`);
      setConfirmPlan(false);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Shell active="/admin">
      <header className="topbar">
        <div>
          <p className="eyebrow">Organisation</p>
          <h1>Tenant admin</h1>
          <p className="sub">Usage, plan, quotas, and payment-provider configuration for this tenant.</p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {note && <p className="ok">{note}</p>}

      <section className="grid metrics">
        <article className="card">
          <span>Transfers created (this month)</span>
          <strong>{transfers ?? "—"}</strong>
        </article>
        {quota &&
          Object.entries(quota).map(([k, v]) => (
            <article className="card" key={k}>
              <span>Quota · {k.replace(/_/g, " ")}</span>
              <strong>{v}</strong>
            </article>
          ))}
      </section>

      <section className="panel">
        <div className="panelHeader">
          <div>
            <h2>Plan</h2>
            <p className="sub">Changing the plan emits a billing event and is recorded in the audit log.</p>
          </div>
        </div>
        <div className="panelBody">
          <div className="row">
            <select value={plan} onChange={(e) => setPlan(e.target.value)} style={{ maxWidth: 240 }} aria-label="Plan">
              {PLANS.map((p) => (
                <option key={p} value={p}>{p.replace(/_/g, " ")}</option>
              ))}
            </select>
            <button onClick={() => setConfirmPlan(true)}>Change plan</button>
          </div>
          {events.length > 0 && (
            <p className="hint" style={{ marginTop: 10 }}>
              Recent billing events: {events.slice(0, 6).join(", ")}
            </p>
          )}
        </div>
      </section>

      <section className="panel" style={{ marginTop: 18 }}>
        <div className="panelHeader">
          <div>
            <h2>Payment provider configs</h2>
            <p className="sub">Secrets are write-only — never returned by the API after creation.</p>
          </div>
        </div>
        <table>
          <thead>
            <tr>
              <th>Provider</th>
              <th>Environment</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {configs.map((c, i) => (
              <tr key={i}>
                <td>{c.provider}</td>
                <td className="muted">{c.environment}</td>
                <td><StatusPill value={c.enabled ? "ACTIVE" : "DISABLED"} /></td>
              </tr>
            ))}
            {configs.length === 0 && (
              <tr>
                <td colSpan={3} className="muted">No provider configs yet — the sandbox rail works without one.</td>
              </tr>
            )}
          </tbody>
        </table>
      </section>

      <ConfirmModal
        open={confirmPlan}
        title={`Change plan to ${plan.replace(/_/g, " ")}`}
        body="This changes billing for the whole tenant and emits a billing event. The change is audited."
        confirmWord="CHANGE"
        confirmLabel="Change plan"
        busy={busy}
        onConfirm={applyPlan}
        onCancel={() => setConfirmPlan(false)}
      />
    </Shell>
  );
}
