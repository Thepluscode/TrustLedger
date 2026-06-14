"use client";

import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { ConfirmModal, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import type { BandCounts, FraudPolicy, PolicyImpact } from "../lib/types";

const PLANS = ["FREE_SANDBOX", "PILOT", "PROFESSIONAL", "ENTERPRISE", "INTERNAL"];

const POLICY_FIELDS: { key: keyof Pick<FraudPolicy, "monitor" | "mfa" | "hold" | "reject">; label: string; hint: string }[] = [
  { key: "monitor", label: "Monitor", hint: "≥ this score is allowed but flagged for monitoring" },
  { key: "mfa", label: "Step-up (MFA)", hint: "≥ this score requires inline step-up verification" },
  { key: "hold", label: "Hold", hint: "≥ this score is held for analyst review" },
  { key: "reject", label: "Reject", hint: "≥ this score is declined outright" },
];

const BANDS: { key: keyof Omit<BandCounts, "total">; label: string }[] = [
  { key: "allow", label: "Allow" },
  { key: "monitor", label: "Monitor" },
  { key: "mfa", label: "Step-up" },
  { key: "hold", label: "Hold" },
  { key: "reject", label: "Reject" },
];

export default function AdminPage() {
  const [transfers, setTransfers] = useState<number | null>(null);
  const [quota, setQuota] = useState<Record<string, number> | null>(null);
  const [events, setEvents] = useState<string[]>([]);
  const [configs, setConfigs] = useState<{ provider: string; environment: string; enabled: boolean }[]>([]);
  const [plan, setPlan] = useState("PILOT");
  const [confirmPlan, setConfirmPlan] = useState(false);
  const [policy, setPolicy] = useState<FraudPolicy | null>(null);
  const [impact, setImpact] = useState<PolicyImpact | null>(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  function load() {
    api.getUsage("transfers_created").then((u) => setTransfers(u.currentMonth)).catch(() => {});
    api.getTenantQuota().then(setQuota).catch((e) => setError((e as Error).message));
    api.getBillingEvents().then(setEvents).catch(() => {});
    api.listProviderConfigs().then(setConfigs).catch(() => {});
    api.getFraudPolicy().then(setPolicy).catch((e) => setError((e as Error).message));
  }
  useEffect(load, []);

  // The bands must be a non-decreasing ladder, each within 0–100.
  const policyValid =
    !!policy &&
    [policy.monitor, policy.mfa, policy.hold, policy.reject].every((v) => v >= 0 && v <= 100) &&
    policy.monitor <= policy.mfa &&
    policy.mfa <= policy.hold &&
    policy.hold <= policy.reject &&
    policy.deviceTrustAfter >= 0;

  function setPolicyField(key: keyof FraudPolicy, value: number | boolean) {
    setImpact(null); // candidate changed — previous preview is stale
    setPolicy((p) => (p ? { ...p, [key]: value } : p));
  }

  async function previewImpact() {
    if (!policy || !policyValid) return;
    setError(null);
    try {
      setImpact(await api.previewFraudPolicyImpact(policy));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function savePolicy() {
    if (!policy || !policyValid) return;
    setBusy(true);
    setNote(null);
    setError(null);
    try {
      const saved = await api.updateFraudPolicy(policy);
      setPolicy(saved);
      setImpact(null);
      setNote("Fraud policy updated — applies to new transfers immediately.");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

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
            <h2>Fraud policy</h2>
            <p className="sub">
              Per-tenant risk appetite. A transfer&apos;s risk score maps to a band; higher bands add friction.
            </p>
          </div>
        </div>
        <div className="panelBody">
          {!policy ? (
            <div className="skeleton" style={{ maxWidth: 420, minHeight: 22 }} />
          ) : (
            <>
              <div className="row" style={{ gap: 18, flexWrap: "wrap" }}>
                {POLICY_FIELDS.map((f) => (
                  <div key={f.key} style={{ minWidth: 130 }}>
                    <label htmlFor={f.key} style={{ marginTop: 0 }}>{f.label}</label>
                    <input
                      id={f.key}
                      type="number"
                      min={0}
                      max={100}
                      value={policy[f.key]}
                      onChange={(e) => setPolicyField(f.key, Number(e.target.value))}
                      style={{ width: 110 }}
                    />
                    <p className="hint" style={{ maxWidth: 150 }}>{f.hint}</p>
                  </div>
                ))}
                <div style={{ minWidth: 150 }}>
                  <label htmlFor="deviceTrustAfter" style={{ marginTop: 0 }}>Trust device after</label>
                  <input
                    id="deviceTrustAfter"
                    type="number"
                    min={0}
                    value={policy.deviceTrustAfter}
                    onChange={(e) => setPolicyField("deviceTrustAfter", Number(e.target.value))}
                    style={{ width: 110 }}
                  />
                  <p className="hint" style={{ maxWidth: 160 }}>
                    successful transfers before a device is trusted (0 = never)
                  </p>
                </div>
              </div>
              <label className="row" style={{ gap: 8, marginTop: 14, alignItems: "center" }}>
                <input
                  type="checkbox"
                  checked={policy.autoFreezeEnabled}
                  onChange={(e) => setPolicyField("autoFreezeEnabled", e.target.checked)}
                  style={{ width: "auto" }}
                />
                Auto-freeze accounts on critical fraud signals
              </label>
              {!policyValid && (
                <p className="error">Bands must be a non-decreasing ladder (monitor ≤ step-up ≤ hold ≤ reject), each 0–100.</p>
              )}
              <div className="notice" style={{ marginTop: 12 }}>
                Current ladder: <b>&lt;{policy.monitor}</b> allow · <b>{policy.monitor}–{policy.mfa - 1}</b> monitor ·{" "}
                <b>{policy.mfa}–{policy.hold - 1}</b> step-up · <b>{policy.hold}–{policy.reject - 1}</b> hold ·{" "}
                <b>≥{policy.reject}</b> reject.
              </div>
              <div className="row" style={{ marginTop: 14 }}>
                <button onClick={savePolicy} disabled={busy || !policyValid}>
                  {busy ? "Saving…" : "Save policy"}
                </button>
                <button className="secondary" onClick={previewImpact} disabled={!policyValid}>
                  Preview impact
                </button>
              </div>

              {impact && (
                <div style={{ marginTop: 16 }}>
                  <p className="sub">
                    Impact over the last {impact.windowDays} days
                    {impact.candidate.total === 0
                      ? " — no transfers in this window to preview against."
                      : ` (${impact.candidate.total} transfers, re-banded under the candidate thresholds):`}
                  </p>
                  {impact.candidate.total > 0 && (
                    <table style={{ maxWidth: 420 }}>
                      <thead>
                        <tr><th>Band</th><th className="num">Now</th><th className="num">Would be</th><th className="num">Δ</th></tr>
                      </thead>
                      <tbody>
                        {BANDS.map((b) => {
                          const now = impact.current[b.key];
                          const next = impact.candidate[b.key];
                          const delta = next - now;
                          return (
                            <tr key={b.key}>
                              <td>{b.label}</td>
                              <td className="num">{now}</td>
                              <td className="num">{next}</td>
                              <td className="num" style={{ color: delta === 0 ? "var(--muted)" : delta > 0 ? "var(--warning)" : "var(--success)" }}>
                                {delta > 0 ? `+${delta}` : delta}
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  )}
                </div>
              )}
            </>
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
