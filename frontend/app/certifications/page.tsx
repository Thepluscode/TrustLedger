"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { EmptyState, SkeletonRows, StatusPill } from "../components/ui";
import Shell from "../components/Shell";
import { api } from "../lib/api";
import { dateTime, shortId } from "../lib/format";
import type { CertificationRun, ProviderConfigView } from "../lib/types";

/** A config is production-certified iff it has a PASSED, signed-off, unexpired run — the gate's rule. */
function isCertified(runs: CertificationRun[], configId: string): boolean {
  const now = Date.now();
  return runs.some(
    (r) =>
      r.tenantProviderConfigId === configId &&
      r.status === "PASSED" &&
      r.signedOff &&
      (!r.expiresAt || new Date(r.expiresAt).getTime() > now),
  );
}

export default function CertificationsPage() {
  const [runs, setRuns] = useState<CertificationRun[] | null>(null);
  const [configs, setConfigs] = useState<ProviderConfigView[]>([]);
  const [selected, setSelected] = useState<string>("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function load() {
    api.listCertifications().then(setRuns).catch((e) => setError((e as Error).message));
    api.listProviderConfigs().then(setConfigs).catch(() => {});
  }
  useEffect(load, []);

  const prodConfigs = configs.filter((c) => c.environment === "PRODUCTION");

  async function run() {
    if (!selected) return;
    setBusy(true);
    setError(null);
    try {
      await api.runCertification(selected);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const all = runs ?? [];
  const passedRuns = all.filter((run) => run.status === "PASSED").length;
  const certifiedConfigs = prodConfigs.filter((config) => isCertified(all, config.id)).length;
  const awaitingSignOff = all.filter((run) => run.status === "PASSED" && !run.signedOff).length;

  return (
    <Shell active="/certifications">
      <header className="topbar">
        <div>
          <p className="eyebrow">Payment Rails</p>
          <h1>Provider certification</h1>
          <p className="sub">
            A provider integration must pass the drill catalogue and get a dual-control sign-off before it can move
            money in production. Runs execute against the deterministic sandbox rail — no real funds are touched.
          </p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}

      <section className="operations-strip" aria-label="Certification summary">
        <div><span>Production configs</span><strong>{configs.length === 0 && runs === null ? "—" : prodConfigs.length}</strong></div>
        <div><span>Certified</span><strong>{runs ? certifiedConfigs : "—"}</strong></div>
        <div><span>Passed runs</span><strong>{runs ? passedRuns : "—"}</strong></div>
        <div className={awaitingSignOff > 0 ? "attention" : ""}><span>Awaiting sign-off</span><strong>{runs ? awaitingSignOff : "—"}</strong></div>
      </section>

      <section className="panel certification-readiness">
        <div className="panelHeader">
          <div>
            <h2>Production configuration gate</h2>
            <p className="sub">A passed run is not sufficient until another authorised operator signs it off.</p>
          </div>
          <div className="certification-run-control">
            <select value={selected} onChange={(e) => setSelected(e.target.value)} aria-label="Provider config to certify">
              <option value="">Select a production config…</option>
              {prodConfigs.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.provider} · {shortId(c.id)}
                </option>
              ))}
            </select>
            <button onClick={run} disabled={!selected || busy}>
              {busy ? "Running…" : "Start drill run"}
            </button>
          </div>
        </div>
        <table className="desktop-table">
          <thead><tr><th>Provider</th><th>Configuration</th><th>Environment</th><th>Certification gate</th></tr></thead>
          <tbody>
            {prodConfigs.map((config) => (
              <tr key={config.id}>
                <td>{config.provider}</td>
                <td className="muted">
                  <span className="mono">{shortId(config.id)}</span>
                </td>
                <td>{config.environment.toLowerCase()}</td>
                <td><StatusPill value={isCertified(all, config.id) ? "CERTIFIED" : "NOT_CERTIFIED"} /></td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="mobile-record-list">
          {prodConfigs.map((config) => (
            <article className="mobile-record certification-config-record" key={config.id}>
              <div className="record-head"><div><small>Provider</small><strong>{config.provider}</strong></div><StatusPill value={isCertified(all, config.id) ? "CERTIFIED" : "NOT_CERTIFIED"} /></div>
              <small className="mono">{config.id}</small>
              <div className="record-foot"><span>{config.environment.toLowerCase()}</span><span>{config.operationalStatus.toLowerCase()}</span></div>
            </article>
          ))}
        </div>
        {runs !== null && prodConfigs.length === 0 && (
          <EmptyState
            title="No production provider configs"
            hint="Add a production provider configuration under Tenant Admin before certifying it."
          />
        )}
      </section>

      <section className="panel certification-history" style={{ marginTop: 18 }}>
        <div className="panelHeader">
          <div>
            <h2>Certification runs</h2>
          </div>
        </div>
        <table className="desktop-table">
          <thead>
            <tr>
              <th>Run</th>
              <th>Config</th>
              <th>Status</th>
              <th>Sign-off</th>
              <th>Expires</th>
              <th>Completed</th>
            </tr>
          </thead>
          <tbody>
            {runs === null && <SkeletonRows cols={6} />}
            {all.map((r) => (
              <tr key={r.id}>
                <td>
                  <Link href={`/certifications/${r.id}`}>{shortId(r.id)}</Link>
                </td>
                <td className="muted">
                  <span className="mono">{shortId(r.tenantProviderConfigId)}</span>
                </td>
                <td>
                  <StatusPill value={r.status} />
                </td>
                <td>{r.signedOff ? <StatusPill value="SIGNED_OFF" /> : <span className="muted">—</span>}</td>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{r.expiresAt ? dateTime(r.expiresAt) : "—"}</td>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{r.completedAt ? dateTime(r.completedAt) : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="mobile-record-list">
          {all.map((run) => (
            <Link className="mobile-record certification-run-record" href={`/certifications/${run.id}`} key={run.id}>
              <div className="record-head"><div><small>Run</small><strong className="mono">{shortId(run.id)}</strong></div><StatusPill value={run.status} /></div>
              <div className="record-stats"><span><small>Sign-off</small><b>{run.signedOff ? "Complete" : "Required"}</b></span><span><small>Completed</small><b>{run.completedAt ? dateTime(run.completedAt) : "—"}</b></span></div>
              <small>Configuration {shortId(run.tenantProviderConfigId)} · valid until {run.expiresAt ? dateTime(run.expiresAt) : "—"}</small>
            </Link>
          ))}
        </div>
        {runs !== null && all.length === 0 && (
          <EmptyState
            title="No certifications yet"
            hint="Select a production config above and run one. A passed run then needs a second person's sign-off to open the gate."
          />
        )}
      </section>
      {prodConfigs.length > 0 && <button className="mobile-primary-action" onClick={run} disabled={!selected || busy}>{busy ? "Running…" : "Start certification"}</button>}
    </Shell>
  );
}
