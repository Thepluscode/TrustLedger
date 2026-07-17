"use client";

import { type FormEvent, useEffect, useMemo, useState } from "react";
import Shell from "../components/Shell";
import { ConfirmModal, StatusPill } from "../components/ui";
import { api, getSession } from "../lib/api";
import type {
  ProductionCanaryRequest,
  ProductionCanaryView,
  ProviderConfigView,
} from "../lib/types";

const APPROVER_ROLES = new Set(["OWNER", "ADMIN", "TENANT_ADMIN"]);

function localDateTime(date: Date): string {
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

function displayDate(value: string | null): string {
  if (!value) return "—";
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}

function number(value: number): string {
  return new Intl.NumberFormat(undefined, { maximumFractionDigits: 4 }).format(value);
}

function percent(value: number, maximum: number): number {
  if (!Number.isFinite(value) || !Number.isFinite(maximum) || maximum <= 0) return 0;
  return Math.min(100, Math.max(0, (value / maximum) * 100));
}

function readiness(config: ProviderConfigView): { label: string; ready: boolean }[] {
  return [
    { label: "Compliance approved", ready: config.complianceStatus === "APPROVED" },
    { label: "Provider operational", ready: config.operationalStatus === "ACTIVE" },
    { label: "Tenant control enabled", ready: config.enabled },
    { label: "Emergency stop clear", ready: !config.emergencyDisabled },
    { label: "Execution credentials configured", ready: config.credentialsConfigured },
    { label: "Webhook credentials configured", ready: config.webhookSecretConfigured },
  ];
}

type ConfirmAction =
  | { kind: "approve"; plan: ProductionCanaryView }
  | { kind: "resume"; plan: ProductionCanaryView }
  | null;

export default function ProductionReadinessPage() {
  const [configs, setConfigs] = useState<ProviderConfigView[]>([]);
  const [selectedConfigId, setSelectedConfigId] = useState("");
  const [canaries, setCanaries] = useState<ProductionCanaryView[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [confirmAction, setConfirmAction] = useState<ConfirmAction>(null);
  const [pausePlanId, setPausePlanId] = useState<string | null>(null);
  const [pauseReason, setPauseReason] = useState("");

  const now = useMemo(() => new Date(), []);
  const [form, setForm] = useState({
    startsAt: localDateTime(new Date(now.getTime() + 5 * 60_000)),
    expiresAt: localDateTime(new Date(now.getTime() + 3 * 60 * 60_000)),
    maxTransactionAmount: "50000",
    maxCumulativeAmount: "250000",
    maxTransactions: "10",
    failurePauseThreshold: "1",
    unknownPauseThreshold: "1",
    reversalPauseThreshold: "1",
  });

  const session = getSession();
  const canApprove = APPROVER_ROLES.has((session?.role ?? "").toUpperCase());
  const productionConfigs = configs.filter((config) => config.environment === "PRODUCTION");
  const selectedConfig = productionConfigs.find((config) => config.id === selectedConfigId) ?? null;
  const checks = selectedConfig ? readiness(selectedConfig) : [];
  const providerReady = checks.length > 0 && checks.every((check) => check.ready);

  async function loadConfigs() {
    const all = await api.listProviderConfigs();
    setConfigs(all);
    const production = all.filter((config) => config.environment === "PRODUCTION");
    setSelectedConfigId((current) =>
      current && production.some((config) => config.id === current) ? current : production[0]?.id ?? "",
    );
  }

  async function loadCanaries(configId: string) {
    if (!configId) {
      setCanaries([]);
      return;
    }
    setCanaries(await api.listProductionCanaries(configId));
  }

  async function reload() {
    setError(null);
    await loadConfigs();
  }

  useEffect(() => {
    reload()
      .catch((failure) => setError((failure as Error).message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (!selectedConfigId) {
      setCanaries([]);
      return;
    }
    setLoading(true);
    loadCanaries(selectedConfigId)
      .catch((failure) => setError((failure as Error).message))
      .finally(() => setLoading(false));
  }, [selectedConfigId]);

  function updateForm(field: keyof typeof form, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  async function requestCanary(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedConfigId) return;
    const body: ProductionCanaryRequest = {
      startsAt: new Date(form.startsAt).toISOString(),
      expiresAt: new Date(form.expiresAt).toISOString(),
      maxTransactionAmount: Number(form.maxTransactionAmount),
      maxCumulativeAmount: Number(form.maxCumulativeAmount),
      maxTransactions: Number(form.maxTransactions),
      failurePauseThreshold: Number(form.failurePauseThreshold),
      unknownPauseThreshold: Number(form.unknownPauseThreshold),
      reversalPauseThreshold: Number(form.reversalPauseThreshold),
    };
    setBusy(true);
    setError(null);
    setNote(null);
    try {
      await api.requestProductionCanary(selectedConfigId, body);
      setNote("Production canary requested. A different tenant administrator must approve it before any live exposure is eligible.");
      await loadCanaries(selectedConfigId);
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function confirmGovernanceAction() {
    if (!confirmAction || !selectedConfigId) return;
    setBusy(true);
    setError(null);
    setNote(null);
    try {
      if (confirmAction.kind === "approve") {
        await api.approveProductionCanary(selectedConfigId, confirmAction.plan.id);
        setNote("Canary approved. Production routing remains subject to the platform kill switch and every configured exposure limit.");
      } else {
        await api.resumeProductionCanary(selectedConfigId, confirmAction.plan.id);
        setNote("Canary resumed inside its original approved window and limits.");
      }
      setConfirmAction(null);
      await loadCanaries(selectedConfigId);
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function pauseCanary(plan: ProductionCanaryView) {
    if (!selectedConfigId || !pauseReason.trim()) return;
    setBusy(true);
    setError(null);
    setNote(null);
    try {
      await api.pauseProductionCanary(selectedConfigId, plan.id, pauseReason.trim());
      setPausePlanId(null);
      setPauseReason("");
      setNote("Canary paused. New production routing for this provider configuration now fails closed.");
      await loadCanaries(selectedConfigId);
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Shell active="/production-readiness">
      <header className="topbar">
        <div>
          <p className="eyebrow">Payment operations</p>
          <h1>Production readiness</h1>
          <p className="sub">
            Govern live payout exposure with independent approval, hard value limits, automatic circuit breaking,
            and an immutable operating history.
          </p>
        </div>
      </header>

      <div className="notice" style={{ marginBottom: 18 }}>
        <b>Fail-closed boundary:</b> an approved canary does not enable production by itself. The platform-wide
        production execution switch remains a separate server-side control.
      </div>
      {error && <p className="error">{error}</p>}
      {note && <p className="ok">{note}</p>}

      <section className="panel">
        <div className="panelHeader">
          <div>
            <h2>Provider readiness</h2>
            <p className="sub">Select the exact production provider configuration being certified.</p>
          </div>
        </div>
        <div className="panelBody">
          {productionConfigs.length === 0 ? (
            <p className="muted">No production provider configuration exists. Create and approve one in Tenant Admin first.</p>
          ) : (
            <>
              <label htmlFor="production-provider" style={{ marginTop: 0 }}>Production provider configuration</label>
              <select
                id="production-provider"
                value={selectedConfigId}
                onChange={(event) => setSelectedConfigId(event.target.value)}
                style={{ maxWidth: 520 }}
              >
                {productionConfigs.map((config) => (
                  <option key={config.id} value={config.id}>
                    {config.provider} · {config.id.slice(0, 8)}
                  </option>
                ))}
              </select>

              {selectedConfig && (
                <div className="grid metrics" style={{ marginTop: 18 }}>
                  {checks.map((check) => (
                    <article className="card" key={check.label}>
                      <span>{check.label}</span>
                      <strong style={{ fontSize: 18 }}>{check.ready ? "Ready" : "Blocked"}</strong>
                      <StatusPill value={check.ready ? "ACTIVE" : "FAILED"} />
                    </article>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      </section>

      {selectedConfig && (
        <section className="panel" style={{ marginTop: 18 }}>
          <div className="panelHeader">
            <div>
              <h2>Request controlled exposure</h2>
              <p className="sub">Limits are immutable after creation. Expansion requires another independently approved plan.</p>
            </div>
            <StatusPill value={providerReady ? "READY" : "BLOCKED"} />
          </div>
          <form className="panelBody" onSubmit={requestCanary}>
            <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))", gap: 14 }}>
              <div>
                <label htmlFor="startsAt" style={{ marginTop: 0 }}>Starts</label>
                <input id="startsAt" type="datetime-local" value={form.startsAt} onChange={(e) => updateForm("startsAt", e.target.value)} required />
              </div>
              <div>
                <label htmlFor="expiresAt" style={{ marginTop: 0 }}>Expires</label>
                <input id="expiresAt" type="datetime-local" value={form.expiresAt} onChange={(e) => updateForm("expiresAt", e.target.value)} required />
              </div>
              <div>
                <label htmlFor="maxTransactionAmount" style={{ marginTop: 0 }}>Maximum per payout</label>
                <input id="maxTransactionAmount" type="number" min="0.01" step="0.01" value={form.maxTransactionAmount} onChange={(e) => updateForm("maxTransactionAmount", e.target.value)} required />
              </div>
              <div>
                <label htmlFor="maxCumulativeAmount" style={{ marginTop: 0 }}>Maximum cumulative value</label>
                <input id="maxCumulativeAmount" type="number" min="0.01" step="0.01" value={form.maxCumulativeAmount} onChange={(e) => updateForm("maxCumulativeAmount", e.target.value)} required />
              </div>
              <div>
                <label htmlFor="maxTransactions" style={{ marginTop: 0 }}>Maximum payouts</label>
                <input id="maxTransactions" type="number" min="1" step="1" value={form.maxTransactions} onChange={(e) => updateForm("maxTransactions", e.target.value)} required />
              </div>
            </div>

            <h3 style={{ marginTop: 22 }}>Automatic abort thresholds</h3>
            <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(190px, 1fr))", gap: 14 }}>
              <div>
                <label htmlFor="failureThreshold" style={{ marginTop: 0 }}>Authoritative failures</label>
                <input id="failureThreshold" type="number" min="1" step="1" value={form.failurePauseThreshold} onChange={(e) => updateForm("failurePauseThreshold", e.target.value)} required />
              </div>
              <div>
                <label htmlFor="unknownThreshold" style={{ marginTop: 0 }}>Ambiguous outcomes</label>
                <input id="unknownThreshold" type="number" min="1" step="1" value={form.unknownPauseThreshold} onChange={(e) => updateForm("unknownPauseThreshold", e.target.value)} required />
              </div>
              <div>
                <label htmlFor="reversalThreshold" style={{ marginTop: 0 }}>Provider reversals</label>
                <input id="reversalThreshold" type="number" min="1" step="1" value={form.reversalPauseThreshold} onChange={(e) => updateForm("reversalPauseThreshold", e.target.value)} required />
              </div>
            </div>

            <button style={{ marginTop: 18 }} disabled={busy || !providerReady} type="submit">
              {busy ? "Submitting…" : "Request production canary"}
            </button>
            {!providerReady && <p className="hint">Resolve every provider-readiness blocker before requesting production exposure.</p>}
          </form>
        </section>
      )}

      <section className="panel" style={{ marginTop: 18 }}>
        <div className="panelHeader">
          <div>
            <h2>Rollout history</h2>
            <p className="sub">Current and previous generations remain visible for incident inheritance and auditability.</p>
          </div>
          <button className="secondary" onClick={() => selectedConfigId && loadCanaries(selectedConfigId)} disabled={!selectedConfigId || loading}>
            Refresh
          </button>
        </div>
        <div className="panelBody">
          {loading && <div className="skeleton" style={{ minHeight: 24, maxWidth: 500 }} />}
          {!loading && canaries.length === 0 && <p className="muted">No canary plan exists for this provider configuration.</p>}
          <div style={{ display: "grid", gap: 16 }}>
            {canaries.map((plan) => {
              const countPct = percent(plan.reservedTransactions, plan.maxTransactions);
              const valuePct = percent(plan.reservedAmount, plan.maxCumulativeAmount);
              const actionablePause = plan.status === "ACTIVE" || plan.status === "EXHAUSTED";
              return (
                <article className="card" key={plan.id} style={{ padding: 18 }}>
                  <div className="row" style={{ alignItems: "center", justifyContent: "space-between", gap: 12, flexWrap: "wrap" }}>
                    <div>
                      <div className="row" style={{ alignItems: "center", gap: 8 }}>
                        <strong className="mono">{plan.id.slice(0, 8)}</strong>
                        <StatusPill value={plan.status} />
                      </div>
                      <p className="muted" style={{ marginTop: 6 }}>
                        {displayDate(plan.startsAt)} → {displayDate(plan.expiresAt)}
                      </p>
                    </div>
                    <div className="row" style={{ gap: 8, flexWrap: "wrap" }}>
                      {plan.status === "PENDING_APPROVAL" && canApprove && (
                        <button onClick={() => setConfirmAction({ kind: "approve", plan })}>Approve</button>
                      )}
                      {actionablePause && canApprove && (
                        <button className="danger" onClick={() => { setPausePlanId(plan.id); setPauseReason(""); }}>
                          Pause
                        </button>
                      )}
                      {plan.status === "PAUSED" && canApprove && (
                        <button onClick={() => setConfirmAction({ kind: "resume", plan })}>Resume</button>
                      )}
                    </div>
                  </div>

                  {plan.status === "PENDING_APPROVAL" && !canApprove && (
                    <p className="notice" style={{ marginTop: 12 }}>
                      Independent approval is required from an OWNER, ADMIN, or TENANT_ADMIN.
                    </p>
                  )}
                  {plan.pauseReason && <p className="error" style={{ marginTop: 12 }}>Pause reason: {plan.pauseReason.replace(/_/g, " ")}</p>}

                  <div className="split" style={{ marginTop: 14 }}>
                    <div>
                      <h3>Exposure</h3>
                      <p>{plan.reservedTransactions} / {plan.maxTransactions} payouts</p>
                      <div style={{ height: 8, background: "var(--line)", borderRadius: 999, overflow: "hidden" }}>
                        <div style={{ width: `${countPct}%`, height: "100%", background: "var(--accent)" }} />
                      </div>
                      <p style={{ marginTop: 10 }}>{number(plan.reservedAmount)} / {number(plan.maxCumulativeAmount)} cumulative value</p>
                      <div style={{ height: 8, background: "var(--line)", borderRadius: 999, overflow: "hidden" }}>
                        <div style={{ width: `${valuePct}%`, height: "100%", background: "var(--accent)" }} />
                      </div>
                      <p className="hint">Maximum per payout: {number(plan.maxTransactionAmount)}</p>
                    </div>
                    <div>
                      <h3>Observed outcomes</h3>
                      <div className="entry"><span className="muted">Settled</span><b>{plan.settledTransactions}</b></div>
                      <div className="entry"><span className="muted">Failed</span><b>{plan.failedTransactions}</b></div>
                      <div className="entry"><span className="muted">Ambiguous</span><b>{plan.unknownTransactions}</b></div>
                      <div className="entry"><span className="muted">Reversed</span><b>{plan.reversedTransactions}</b></div>
                    </div>
                  </div>

                  <div className="entry" style={{ marginTop: 10 }}>
                    <span className="muted">Requested by</span>
                    <span className="mono">{plan.requestedBy.slice(0, 8)}</span>
                  </div>
                  <div className="entry">
                    <span className="muted">Approved by</span>
                    <span className="mono">{plan.approvedBy ? plan.approvedBy.slice(0, 8) : "—"}</span>
                  </div>
                  <div className="entry">
                    <span className="muted">Approved at</span>
                    <span>{displayDate(plan.approvedAt)}</span>
                  </div>

                  {pausePlanId === plan.id && (
                    <div className="notice" style={{ marginTop: 14 }}>
                      <label htmlFor={`pause-${plan.id}`} style={{ marginTop: 0 }}>Operational pause reason</label>
                      <textarea
                        id={`pause-${plan.id}`}
                        value={pauseReason}
                        maxLength={120}
                        onChange={(event) => setPauseReason(event.target.value)}
                        placeholder="State the evidence-backed reason for stopping new production routing."
                      />
                      <div className="row" style={{ gap: 8, marginTop: 10 }}>
                        <button className="danger" onClick={() => pauseCanary(plan)} disabled={busy || !pauseReason.trim()}>
                          Confirm pause
                        </button>
                        <button className="secondary" onClick={() => { setPausePlanId(null); setPauseReason(""); }}>
                          Cancel
                        </button>
                      </div>
                    </div>
                  )}
                </article>
              );
            })}
          </div>
        </div>
      </section>

      <ConfirmModal
        open={confirmAction !== null}
        title={confirmAction?.kind === "approve" ? "Approve production canary" : "Resume production canary"}
        body={
          confirmAction?.kind === "approve"
            ? "This authorises a bounded production rollout for the exact provider configuration. The platform kill switch, time window, value ceilings, count ceilings, and circuit breakers remain authoritative."
            : "This restores eligibility only inside the original approved window and immutable exposure limits."
        }
        confirmWord={confirmAction?.kind === "approve" ? "APPROVE" : "RESUME"}
        confirmLabel={confirmAction?.kind === "approve" ? "Approve canary" : "Resume canary"}
        busy={busy}
        onConfirm={confirmGovernanceAction}
        onCancel={() => setConfirmAction(null)}
      />
    </Shell>
  );
}
