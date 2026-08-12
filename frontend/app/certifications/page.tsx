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
  const cards = runs
    ? [
        { label: "Runs", value: all.length },
        { label: "Passed", value: all.filter((r) => r.status === "PASSED").length },
        { label: "Certified configs", value: prodConfigs.filter((c) => isCertified(all, c.id)).length },
        {
          label: "Awaiting sign-off",
          value: all.filter((r) => r.status === "PASSED" && !r.signedOff).length,
          alert: all.some((r) => r.status === "PASSED" && !r.signedOff),
        },
      ]
    : [];

  return (
    <Shell active="/certifications">
      <header className="topbar">
        <div>
          <p className="eyebrow">Payment Rails</p>
          <h1>Provider Certification</h1>
          <p className="sub">
            A provider integration must pass the drill catalogue and get a dual-control sign-off before it can move
            money in production. Runs execute against the deterministic sandbox rail — no real funds are touched.
          </p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}

      <section className="grid metrics">
        {cards.length === 0 && !error
          ? Array.from({ length: 4 }, (_, i) => (
              <article className="card" key={i}>
                <div className="skeleton" style={{ width: "55%" }} />
                <div className="skeleton" style={{ width: "30%", minHeight: 26 }} />
              </article>
            ))
          : cards.map((c) => (
              <article className={`card${c.alert ? " alert" : ""}`} key={c.label}>
                <span>{c.label}</span>
                <strong>{c.value}</strong>
              </article>
            ))}
      </section>

      <section className="panel">
        <div className="panelHeader">
          <div>
            <h2>Production readiness</h2>
            <p className="sub">Whether each production provider config currently clears the certification gate.</p>
          </div>
          <div className="row" style={{ gap: 8 }}>
            <select value={selected} onChange={(e) => setSelected(e.target.value)} aria-label="Provider config to certify">
              <option value="">Select a production config…</option>
              {prodConfigs.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.provider} · {shortId(c.id)}
                </option>
              ))}
            </select>
            <button onClick={run} disabled={!selected || busy}>
              {busy ? "Running…" : "Run certification"}
            </button>
          </div>
        </div>
        <table>
          <thead>
            <tr>
              <th>Provider</th>
              <th>Config</th>
              <th>Gate</th>
            </tr>
          </thead>
          <tbody>
            {prodConfigs.map((c) => (
              <tr key={c.id}>
                <td>{c.provider}</td>
                <td className="muted">
                  <span className="mono">{shortId(c.id)}</span>
                </td>
                <td>
                  <StatusPill value={isCertified(all, c.id) ? "CERTIFIED" : "NOT_CERTIFIED"} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {runs !== null && prodConfigs.length === 0 && (
          <EmptyState
            title="No production provider configs"
            hint="Add a production provider configuration under Tenant Admin before certifying it."
          />
        )}
      </section>

      <section className="panel" style={{ marginTop: 18 }}>
        <div className="panelHeader">
          <div>
            <h2>Certification runs</h2>
          </div>
        </div>
        <table>
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
        {runs !== null && all.length === 0 && (
          <EmptyState
            title="No certifications yet"
            hint="Select a production config above and run one. A passed run then needs a second person's sign-off to open the gate."
          />
        )}
      </section>
    </Shell>
  );
}
