"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { StatusPill } from "../../components/ui";
import Shell from "../../components/Shell";
import { api } from "../../lib/api";
import { dateTime, shortId } from "../../lib/format";
import type { CertificationRun, DrillResultView } from "../../lib/types";

interface Assertion {
  name: string;
  expected: string;
  actual: string;
  ok: boolean;
}

function assertionsOf(drill: DrillResultView): Assertion[] {
  const detail = drill.detail as { assertions?: Assertion[] } | null;
  return detail?.assertions ?? [];
}

export default function CertificationRunPage() {
  const params = useParams<{ runId: string }>();
  const id = params.runId;
  const [run, setRun] = useState<CertificationRun | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (id) api.getCertification(id).then(setRun).catch((e) => setError((e as Error).message));
  }, [id]);

  async function signOff() {
    if (!id) return;
    setBusy(true);
    setError(null);
    try {
      setRun(await api.signOffCertification(id, note));
      setNote("");
    } catch (e) {
      // The backend rejects the run's initiator, a non-PASSED run, or a second sign-off — surface it.
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Shell active="/certifications">
      <header className="topbar">
        <div>
          <p className="eyebrow">
            <Link href="/certifications">Certifications</Link> / run
          </p>
          <h1>Run {shortId(id)}</h1>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {!run && !error && <div className="skeleton" style={{ maxWidth: 480, minHeight: 24 }} />}

      {run && (
        <>
          <section className="panel">
            <div className="panelBody">
              <p className="row" style={{ gap: 10, alignItems: "center" }}>
                <StatusPill value={run.status} />
                {run.signedOff ? <StatusPill value="SIGNED_OFF" /> : <span className="muted">not signed off</span>}
                <span className="muted">{run.environment}</span>
              </p>
              <div style={{ maxWidth: 620 }}>
                <div className="entry">
                  <span className="muted">Provider config</span>
                  <span className="mono">{shortId(run.tenantProviderConfigId)}</span>
                </div>
                <div className="entry">
                  <span className="muted">Catalogue version</span>
                  <span className="mono">{run.catalogueVersion}</span>
                </div>
                <div className="entry">
                  <span className="muted">Completed</span>
                  <span>{run.completedAt ? dateTime(run.completedAt) : "—"}</span>
                </div>
                <div className="entry">
                  <span className="muted">Valid until</span>
                  <span>{run.expiresAt ? dateTime(run.expiresAt) : "—"}</span>
                </div>
              </div>
              {run.evidenceExportId && (
                <div className="row" style={{ marginTop: 16 }}>
                  <button onClick={() => api.downloadEvidence(run.evidenceExportId as string)}>
                    Download evidence pack
                  </button>
                </div>
              )}
            </div>
          </section>

          {run.status === "PASSED" && !run.signedOff && (
            <section className="panel" style={{ marginTop: 18 }}>
              <div className="panelHeader">
                <div>
                  <h2>Dual-control sign-off</h2>
                  <p className="sub">
                    A second reviewer — not the run&apos;s initiator — confirms this certification. Recorded in the
                    audit log; it opens the production gate for this provider config.
                  </p>
                </div>
              </div>
              <div className="panelBody">
                <div className="row" style={{ gap: 8 }}>
                  <input
                    value={note}
                    onChange={(e) => setNote(e.target.value)}
                    placeholder="Sign-off note (e.g. reviewed drill evidence)"
                    style={{ minWidth: 320 }}
                    aria-label="Sign-off note"
                  />
                  <button onClick={signOff} disabled={busy}>
                    {busy ? "Signing off…" : "Sign off"}
                  </button>
                </div>
              </div>
            </section>
          )}

          <section className="panel" style={{ marginTop: 18 }}>
            <div className="panelHeader">
              <div>
                <h2>Drills</h2>
                <p className="sub">Each drill and its assertions — expected vs actual, read back from real state.</p>
              </div>
            </div>
            <div className="panelBody">
              {run.drills.map((drill) => (
                <div key={drill.drillId} style={{ marginBottom: 18 }}>
                  <p className="row" style={{ gap: 10, alignItems: "center", marginBottom: 6 }}>
                    <StatusPill value={drill.status} />
                    <strong>{drill.drillId.replace(/_/g, " ")}</strong>
                    <span className="muted">v{drill.drillVersion}</span>
                  </p>
                  <table>
                    <thead>
                      <tr>
                        <th>Assertion</th>
                        <th>Expected</th>
                        <th>Actual</th>
                        <th>Result</th>
                      </tr>
                    </thead>
                    <tbody>
                      {assertionsOf(drill).map((a) => (
                        <tr key={a.name}>
                          <td>{a.name.replace(/_/g, " ")}</td>
                          <td className="muted">
                            <span className="mono">{a.expected}</span>
                          </td>
                          <td className="muted">
                            <span className="mono">{a.actual}</span>
                          </td>
                          <td>
                            <StatusPill value={a.ok ? "PASS" : "FAIL"} />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ))}
            </div>
          </section>
        </>
      )}
    </Shell>
  );
}
