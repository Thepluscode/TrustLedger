"use client";

import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { ConfirmModal, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import type {
  BandCounts,
  FraudPolicy,
  PolicyImpact,
  ProviderConfigView,
  ProviderCredentialView,
} from "../lib/types";

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
  const [configs, setConfigs] = useState<ProviderConfigView[]>([]);
  // Credentials are loaded per config, on demand: the list endpoint is per-config and eagerly
  // fetching them all would issue one request per provider on every page load.
  const [openConfigId, setOpenConfigId] = useState<string | null>(null);
  const [credentials, setCredentials] = useState<ProviderCredentialView[] | null>(null);
  const [credentialPurpose, setCredentialPurpose] = useState("API");
  const [credentialSecretRef, setCredentialSecretRef] = useState("");
  const [graceSeconds, setGraceSeconds] = useState(300);
  const [providerBusy, setProviderBusy] = useState(false);
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

  /** Runs a provider action, surfacing failure rather than leaving the button dead. */
  async function runProviderAction(message: string, action: () => Promise<unknown>): Promise<void> {
    setProviderBusy(true);
    setError(null);
    setNote(null);
    try {
      await action();
      setNote(message);
      const fresh = await api.listProviderConfigs();
      setConfigs(fresh);
      if (openConfigId) setCredentials(await api.listProviderCredentials(openConfigId));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setProviderBusy(false);
    }
  }

  async function toggleCredentials(configId: string): Promise<void> {
    if (openConfigId === configId) {
      setOpenConfigId(null);
      setCredentials(null);
      return;
    }
    setOpenConfigId(configId);
    setCredentials(null);
    try {
      setCredentials(await api.listProviderCredentials(configId));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  /**
   * Activation is compare-and-set: the currently ACTIVE credential id is sent as the expected value,
   * so a concurrent rotation by another operator is refused by the backend instead of being
   * silently overwritten.
   */
  function activeCredentialId(): string | null {
    return credentials?.find((c) => c.status === "ACTIVE")?.id ?? null;
  }

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

      <section className="grid metrics admin-metrics">
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

      <section className="panel admin-plan">
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

      <section className="panel fraud-policy-panel" style={{ marginTop: 18 }}>
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

      <section className="panel provider-config-list" style={{ marginTop: 18 }}>
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
              <th>Compliance</th>
              <th>Credentials</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {configs.map((c) => (
              <FragmentRow
                key={c.id}
                config={c}
                open={openConfigId === c.id}
                busy={providerBusy}
                credentials={openConfigId === c.id ? credentials : null}
                onToggle={() => toggleCredentials(c.id)}
                onSetEnabled={(enabled) =>
                  runProviderAction(
                    `${c.provider} ${enabled ? "enabled" : "disabled"}.`,
                    () => api.updateProviderControls(c.id, enabled, c.emergencyDisabled),
                  )
                }
                onSetEmergency={(stopped) =>
                  runProviderAction(
                    stopped ? `Emergency stop engaged for ${c.provider}.` : `Emergency stop cleared for ${c.provider}.`,
                    () => api.updateProviderControls(c.id, c.enabled, stopped),
                  )
                }
                onActivate={(credentialId) =>
                  runProviderAction("Credential activated.", () =>
                    api.activateProviderCredential(c.id, credentialId, activeCredentialId(), graceSeconds),
                  )
                }
                onRevoke={(credentialId) =>
                  runProviderAction("Credential revoked.", () => api.revokeProviderCredential(c.id, credentialId))
                }
              />
            ))}
            {configs.length === 0 && (
              <tr>
                <td colSpan={6} className="muted">No provider configs yet — the sandbox rail works without one.</td>
              </tr>
            )}
          </tbody>
        </table>

        {openConfigId && (
          <div className="subpanel">
            <h3>Add a credential version</h3>
            <p className="sub">
              A credential is stored as a <strong>reference</strong>, never as the secret itself. This deployment
              resolves <span className="mono">env://VARIABLE_NAME</span> — the variable must be uppercase, at least
              three characters, and already present in the backend&apos;s environment. Any other scheme is rejected
              with &ldquo;Unsupported secret reference scheme&rdquo;. New versions start PENDING and move no money
              until explicitly activated.
            </p>
            <div className="form-row">
              <label>
                Purpose
                <select value={credentialPurpose} onChange={(e) => setCredentialPurpose(e.target.value)}>
                  <option value="API">API (outbound calls)</option>
                  <option value="WEBHOOK">WEBHOOK (inbound signature verification)</option>
                </select>
              </label>
              <label>
                Secret reference
                <input
                  value={credentialSecretRef}
                  placeholder="env://PAYSTACK_API_KEY"
                  onChange={(e) => setCredentialSecretRef(e.target.value)}
                />
              </label>
              <label>
                Activation grace (s)
                <input
                  type="number"
                  min={0}
                  value={graceSeconds}
                  onChange={(e) => setGraceSeconds(Number(e.target.value))}
                  title="How long the previous credential stays valid for inbound signature verification during cutover"
                />
              </label>
              <button
                disabled={providerBusy || !/^env:\/\/[A-Z][A-Z0-9_]{2,127}$/.test(credentialSecretRef.trim())}
                title="Reference must be env://VARIABLE_NAME (uppercase, 3+ characters)"
                onClick={() =>
                  runProviderAction("Credential version created (PENDING).", async () => {
                    await api.createProviderCredential(openConfigId, credentialPurpose, credentialSecretRef.trim());
                    setCredentialSecretRef("");
                  })
                }
              >
                Add version
              </button>
            </div>
          </div>
        )}
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

/**
 * One provider config row plus its expandable credential list. Split out so the main page body
 * stays readable — the row carries six controls and a nested table.
 */
function FragmentRow({
  config,
  open,
  busy,
  credentials,
  onToggle,
  onSetEnabled,
  onSetEmergency,
  onActivate,
  onRevoke,
}: {
  config: ProviderConfigView;
  open: boolean;
  busy: boolean;
  credentials: ProviderCredentialView[] | null;
  onToggle: () => void;
  onSetEnabled: (enabled: boolean) => void;
  onSetEmergency: (stopped: boolean) => void;
  onActivate: (credentialId: string) => void;
  onRevoke: (credentialId: string) => void;
}) {
  return (
    <>
      <tr>
        <td>
          {config.provider}
          {config.emergencyDisabled && <span className="pill danger" title="Emergency stop engaged">STOPPED</span>}
        </td>
        <td className="muted">{config.environment}</td>
        <td><StatusPill value={config.enabled ? "ACTIVE" : "DISABLED"} /></td>
        <td><StatusPill value={config.complianceStatus} /></td>
        <td className="muted">
          {config.credentialsConfigured ? "API ✓" : "API —"} / {config.webhookSecretConfigured ? "webhook ✓" : "webhook —"}
        </td>
        <td className="row-actions">
          <button className="secondary" disabled={busy} onClick={() => onSetEnabled(!config.enabled)}>
            {config.enabled ? "Disable" : "Enable"}
          </button>
          {/* Emergency stop is separate from enable/disable on purpose: it is the control an operator
              reaches for during an incident, and it must not be confused with routine configuration. */}
          <button
            className={config.emergencyDisabled ? "secondary" : "danger"}
            disabled={busy}
            onClick={() => onSetEmergency(!config.emergencyDisabled)}
          >
            {config.emergencyDisabled ? "Clear stop" : "Emergency stop"}
          </button>
          <button className="secondary" onClick={onToggle}>
            {open ? "Hide credentials" : "Credentials"}
          </button>
        </td>
      </tr>
      {open && (
        <tr>
          <td colSpan={6}>
            {credentials === null && <p className="muted">Loading credentials…</p>}
            {credentials?.length === 0 && (
              <p className="muted">
                No credential versions. This provider cannot execute until an API credential is added and activated.
              </p>
            )}
            {credentials && credentials.length > 0 && (
              <table className="nested">
                <thead>
                  <tr>
                    <th>Purpose</th>
                    <th className="num">Version</th>
                    <th>Status</th>
                    <th>Grace until</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {credentials.map((cr) => (
                    <tr key={cr.id}>
                      <td>{cr.purpose}</td>
                      <td className="num">{cr.versionNumber}</td>
                      <td><StatusPill value={cr.status} /></td>
                      <td className="muted">{cr.graceExpiresAt ?? "—"}</td>
                      <td className="row-actions">
                        {cr.status !== "ACTIVE" && cr.status !== "REVOKED" && (
                          <button className="secondary" disabled={busy} onClick={() => onActivate(cr.id)}>
                            Activate
                          </button>
                        )}
                        {cr.status !== "REVOKED" && (
                          <button className="danger" disabled={busy} onClick={() => onRevoke(cr.id)}>
                            Revoke
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </td>
        </tr>
      )}
    </>
  );
}
