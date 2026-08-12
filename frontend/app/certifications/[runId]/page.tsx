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

  const completedDrills = run?.drills.filter((drill) => !["PENDING", "NOT_RUN"].includes(drill.status)).length ?? 0;
  const drillCount = run?.drills.length ?? 0;
  const progress = drillCount === 0 ? 0 : Math.round((completedDrills / drillCount) * 100);
  const displayStatus = run?.status === "PASSED" && !run.signedOff ? "AWAITING_SIGN_OFF" : run?.status;

  return (
    <Shell active="/certifications">
      <header className="topbar">
        <div>
          <p className="eyebrow">
            <Link href="/certifications">Certifications</Link> / run
          </p>
          <div className="row certification-run-title">
            <h1>Certification run {shortId(id)}</h1>
            {displayStatus && <StatusPill value={displayStatus} />}
          </div>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {!run && !error && <div className="skeleton" style={{ maxWidth: 480, minHeight: 24 }} />}

      {run && (
        <>
          <section className="panel certification-run-overview">
            <div className="panelBody">
              <div className="certification-run-summary">
                <div>
                  <span className="muted">Status</span>
                  <strong>{(displayStatus ?? "UNKNOWN").replace(/_/g, " ").toLowerCase()}</strong>
                  <small>{run.environment}</small>
                </div>
                <div>
                  <span className="muted">Overall progress</span>
                  <strong>{completedDrills} / {drillCount}</strong>
                  <div className="certification-progress"><i style={{ width: `${progress}%` }} /></div>
                  <small>{progress}% complete</small>
                </div>
              </div>
              <div className="certification-run-facts">
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
            </div>
          </section>

          <div className="certification-run-content">
            <section className="panel certification-drills">
              <div className="panelHeader">
                <div>
                  <h2>Drill results</h2>
                  <p className="sub">Each drill and its assertions — expected vs actual, read back from real state.</p>
                </div>
              </div>
              <div className="panelBody">
                {run.drills.map((drill, index) => (
                  <article key={drill.drillId} className="certification-drill">
                    <div className="certification-drill-head">
                      <span className="drill-number">{index + 1}</span>
                      <div><strong>{drill.drillId.replace(/_/g, " ")}</strong><small>Catalogue v{drill.drillVersion}</small></div>
                      <StatusPill value={drill.status} />
                    </div>
                    {assertionsOf(drill).length > 0 && (
                      <table>
                        <thead><tr><th>Assertion</th><th>Expected</th><th>Actual</th><th>Result</th></tr></thead>
                        <tbody>
                          {assertionsOf(drill).map((a) => (
                            <tr key={a.name}>
                              <td>{a.name.replace(/_/g, " ")}</td>
                              <td className="muted"><span className="mono">{a.expected}</span></td>
                              <td className="muted"><span className="mono">{a.actual}</span></td>
                              <td><StatusPill value={a.ok ? "PASS" : "FAIL"} /></td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </article>
                ))}
              </div>
            </section>

            <div className="certification-run-side">
              <section className="panel certification-signoff">
                <div className="panelHeader"><div><h2>Dual-control sign-off</h2><p className="sub">An independent reviewer confirms the evidence. The action is audited.</p></div></div>
                <div className="panelBody">
                  <div className="entry"><span className="muted">Run result</span><StatusPill value={run.status} /></div>
                  <div className="entry"><span className="muted">Independent approval</span>{run.signedOff ? <StatusPill value="SIGNED_OFF" /> : <StatusPill value="PENDING" />}</div>
                  {run.status === "PASSED" && !run.signedOff && (
                    <div className="certification-signoff-form">
                      <label htmlFor="signoff-note">Review note</label>
                      <textarea id="signoff-note" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Evidence reviewed; note any limitations" rows={4} />
                      <button onClick={signOff} disabled={busy}>{busy ? "Signing off…" : "Review and sign off"}</button>
                    </div>
                  )}
                </div>
              </section>
              {run.evidenceExportId && (
                <section className="panel certification-evidence">
                  <div><strong>Evidence pack</strong><span>Checksummed certification evidence</span></div>
                  <button className="secondary" onClick={() => api.downloadEvidence(run.evidenceExportId as string)}>Download</button>
                </section>
              )}
            </div>
          </div>
        </>
      )}
    </Shell>
  );
}
