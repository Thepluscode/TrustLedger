"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { EmptyState, StatusPill } from "../../../components/ui";
import Shell from "../../../components/Shell";
import { api } from "../../../lib/api";
import { bytes, dateTime, money } from "../../../lib/format";
import { IMPORT_PROFILES, matchRatePercent, parseRunSummary } from "../../../lib/recon";
import type { ReconBundle, ReconCaseView, ReconFeed, ReconFeedCreated, ReconImportView, ReconRunView, ReconSourceRow } from "../../../lib/types";

function words(value: string): string {
  return value.replace(/_/g, " ").toLowerCase();
}

export default function ReconCasePage() {
  const { caseId } = useParams<{ caseId: string }>();
  const [view, setView] = useState<ReconCaseView | null>(null);
  const [run, setRun] = useState<ReconRunView | null>(null);
  const [bundle, setBundle] = useState<ReconBundle | null>(null);
  const [rejected, setRejected] = useState<Record<string, ReconSourceRow[]>>({});
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [profile, setProfile] = useState(IMPORT_PROFILES[0].profile);
  const [identity, setIdentity] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [feedList, setFeedList] = useState<ReconFeed[]>([]);
  const [feedIdentity, setFeedIdentity] = useState("");
  const [newFeed, setNewFeed] = useState<ReconFeedCreated | null>(null);

  const load = useCallback(async () => {
    if (!caseId) return;
    try {
      const [loaded, runs, feedRows] = await Promise.all([api.getReconCase(caseId), api.listReconRuns(caseId), api.listReconFeeds(caseId)]);
      setView(loaded);
      setFeedList(feedRows);
      // A DRAFT case has changed since its last run, so that run no longer describes it.
      setRun(runs.length > 0 && loaded.reconciliationCase.status !== "DRAFT" ? await api.getReconRun(caseId, runs[0].id) : null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [caseId]);

  useEffect(() => { void load(); }, [load]);

  async function act(label: string, fn: () => Promise<string | void>) {
    setBusy(label);
    setError(null);
    setNotice(null);
    try {
      const message = await fn();
      if (message) setNotice(message);
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  const upload = (e: React.FormEvent) => {
    e.preventDefault();
    if (!file || !caseId) return;
    const chosen = IMPORT_PROFILES.find((p) => p.profile === profile)!;
    void act("upload", async () => {
      try {
        const r = await api.importReconFile(caseId, chosen.sourceType, identity.trim(), chosen.profile, file);
        setFile(null);
        return r.replayed
          ? `${file.name} was already imported into this case. Nothing was added.`
          : `${file.name}: ${r.manifest.acceptedCount} accepted, ${r.manifest.rejectedCount} rejected, ${r.manifest.duplicateCount} duplicate.`;
      } catch (err) {
        // A file refused as a whole is still recorded (with zero rows) so the refusal is visible below.
        await load();
        throw err;
      }
    });
  };

  async function showRejected(importId: string) {
    if (!caseId || rejected[importId]) return;
    try {
      const rows = await api.reconImportRows(caseId, importId, "REJECTED");
      setRejected((prev) => ({ ...prev, [importId]: rows }));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function download() {
    if (!bundle || !view) return;
    try {
      const blob = await api.downloadEvidenceExport(bundle.exportId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `${view.reconciliationCase.caseRef}-${bundle.bundleStatus.toLowerCase()}-bundle.json`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const c = view?.reconciliationCase;
  const closed = c?.status === "CLOSED";
  const summary = run ? parseRunSummary(run.run.summary) : null;
  const rate = run ? matchRatePercent(run.matchRate) : null;

  return (
    <Shell active="/reconciliation/cases">
      <header className="topbar">
        <div>
          <p className="eyebrow"><Link href="/reconciliation/cases">Cases</Link> / {c?.caseRef ?? "…"}</p>
          <h1>{c?.title ?? "Reconciliation case"}</h1>
          {c && <p className="sub"><StatusPill value={c.status} /> {c.periodStart.slice(0, 10)} → {c.periodEnd.slice(0, 10)} · settlement SLA {c.settlementSlaDays} day(s)</p>}
        </div>
      </header>
      {error && <p className="error" role="alert">{error}</p>}
      {notice && <p className="notice" role="status">{notice}</p>}
      {!view && !error && <div className="skeleton" style={{ maxWidth: 480, minHeight: 24 }} />}

      {view && c && (
        <>
          <section className="panel">
            <div className="panelHeader"><div><h2>1 · Source files</h2><p className="sub">Each file is stored unchanged with its SHA-256 before anything is derived from it.</p></div></div>
            {!closed && (
              <form className="panelBody" onSubmit={upload}>
                <div className="row" style={{ gap: 12, flexWrap: "wrap", alignItems: "flex-end" }}>
                  <label>What is this file?<br />
                    <select value={profile} onChange={(e) => setProfile(e.target.value)}>
                      {IMPORT_PROFILES.map((p) => <option key={p.profile} value={p.profile}>{p.label}</option>)}
                    </select></label>
                  <label>Source system or provider<br /><input value={identity} onChange={(e) => setIdentity(e.target.value)} placeholder="provider-a" required /></label>
                  <label>CSV file<br /><input type="file" accept=".csv,text/csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} required /></label>
                  <button disabled={!file || !identity.trim() || busy !== null}>{busy === "upload" ? "Importing…" : "Import file"}</button>
                </div>
              </form>
            )}
            <div style={{ overflowX: "auto" }}>
            <table>
              <thead><tr><th>File</th><th>Source</th><th>Status</th><th>Rows</th><th>Totals by currency</th><th>Actions</th></tr></thead>
              <tbody>
                {view.imports.map((i: ReconImportView) => {
                  const m = i.manifest;
                  return (
                    <tr key={m.id}>
                      <td>{m.originalFilename}<br /><span className="muted">{bytes(m.byteSize)} · {m.profile} v{m.profileVersion}</span><br />
                        <span className="muted mono" style={{ fontSize: 12 }} title={m.fileSha256}>sha256 {m.fileSha256.slice(0, 16)}…</span><br />
                        <span className="muted" style={{ fontSize: 12 }}>{dateTime(m.importedAt)}</span></td>
                      <td>{words(m.sourceType)}{m.feedId && <span className="muted"> · event</span>}<br /><span className="muted mono">{m.sourceIdentity}</span></td>
                      <td><StatusPill value={m.status} />{m.failureReason && <><br /><span className="error">{m.failureReason}</span></>}</td>
                      <td style={{ whiteSpace: "nowrap" }}>{m.acceptedCount} accepted{m.deliveryCount > 1 && <span className="muted"> · delivered {m.deliveryCount}×</span>}<br />
                        <span className={m.rejectedCount > 0 ? "error" : "muted"}>{m.rejectedCount} rejected</span> · <span className="muted">{m.duplicateCount} duplicate</span></td>
                      <td className="mono">{i.currencyTotals.length === 0 ? "—" : i.currencyTotals.map((t) => <div key={t.currency}>{money(t.grossTotal, t.currency)}</div>)}</td>
                      <td>
                        {m.rejectedCount > 0 && <button className="secondary" onClick={() => showRejected(m.id)}>View rejected</button>}{" "}
                        {m.rejectedCount > 0 && !m.rejectionsAcknowledgedBy && !closed && (
                          <button className="secondary" disabled={busy !== null}
                            onClick={() => act("ack", async () => { await api.acknowledgeReconRejections(caseId, m.id); })}>Acknowledge</button>
                        )}
                        {m.status === "FAILED" && !closed && (
                          <button className="secondary" disabled={busy !== null}
                            onClick={() => act("discard", async () => { await api.discardReconImport(caseId, m.id); })}>Discard</button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            </div>
            {view.imports.length === 0 && <EmptyState title="No files imported" hint="Import the internal ledger export first, then each provider's transaction and settlement files." />}
            {Object.entries(rejected).map(([importId, rows]) => (
              <div className="panelBody" key={importId}>
                <h3>Rejected rows · {view.imports.find((i) => i.manifest.id === importId)?.manifest.originalFilename}</h3>
                <table>
                  <thead><tr><th>Row</th><th>Reason</th><th>Row as supplied</th></tr></thead>
                  <tbody>{rows.map((r) => (
                    <tr key={r.rowNumber}><td>{r.rowNumber}</td><td><span className="mono">{r.rejectionCode}</span><br /><span className="muted">{r.rejectionMessage}</span></td><td className="mono" style={{ fontSize: 12 }}>{r.rawRow}</td></tr>
                  ))}</tbody>
                </table>
              </div>
            ))}
          </section>

          <section className="panel" style={{ marginTop: 18 }}>
            <div className="panelHeader"><div><h2>1b · Live event feeds</h2><p className="sub">A provider posts one JSON event per delivery. Each becomes a one-row import on the same path as a file: stored raw, hashed, deduplicated, reconciled by the same rules.</p></div></div>
            {!closed && (
              <form className="panelBody" onSubmit={(e) => { e.preventDefault(); void act("feed", async () => {
                const created = await api.createReconFeed(caseId, feedIdentity.trim());
                setNewFeed(created);
                setFeedIdentity("");
                return "Feed created. Copy the token now; it is not shown again.";
              }); }}>
                <div className="row" style={{ gap: 12, flexWrap: "wrap", alignItems: "flex-end" }}>
                  <label>Provider identity<br /><input value={feedIdentity} onChange={(e) => setFeedIdentity(e.target.value)} placeholder="provider-b" pattern="[A-Za-z0-9._\-]{1,64}" required /></label>
                  <button className="secondary" disabled={!feedIdentity.trim() || busy !== null}>{busy === "feed" ? "Creating…" : "Create feed"}</button>
                </div>
              </form>
            )}
            {newFeed && (
              <div className="panelBody">
                <p className="notice" style={{ margin: 0 }}>
                  <b>Token (shown once):</b> <span className="mono" style={{ wordBreak: "break-all" }}>{newFeed.token}</span><br />
                  <span className="muted">POST one JSON object per event to <span className="mono">{newFeed.deliveryPath}</span> with header <span className="mono">X-Recon-Feed-Token</span>. Fields: event_id, transaction_ref, merchant_ref, event_type, status, currency, gross, fee, net, occurred_at, received_at.</span>
                </p>
              </div>
            )}
            <table>
              <thead><tr><th>Feed</th><th>Provider</th><th>Profile</th><th>Status</th><th>Created</th><th /></tr></thead>
              <tbody>
                {feedList.map((f) => (
                  <tr key={f.id}>
                    <td className="mono muted" title={f.id}>{f.id.slice(0, 8)}…</td>
                    <td className="mono">{f.providerIdentity}</td>
                    <td className="muted">{f.profile}</td>
                    <td><StatusPill value={f.status} /></td>
                    <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(f.createdAt)}</td>
                    <td>{f.status === "ACTIVE" && !closed && (
                      <button className="secondary" disabled={busy !== null} onClick={() => act("revoke", async () => { await api.revokeReconFeed(caseId, f.id); })}>Revoke</button>
                    )}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {feedList.length === 0 && <EmptyState title="No feeds" hint="Optional. A feed lets a provider deliver events continuously instead of a month-end file. Both reconcile the same way." />}
          </section>

          <section className="panel" style={{ marginTop: 18 }}>
            <div className="panelHeader">
              <div><h2>2 · Reconcile</h2><p className="sub">Deterministic rules only. The same files under the same ruleset always give the same result.</p></div>
              {!closed && <button disabled={view.blockers.length > 0 || busy !== null}
                onClick={() => act("run", async () => { const r = await api.runReconCase(caseId); return r.replayed ? "Nothing has changed since the last run. Showing that run." : "Reconciliation complete."; })}>
                {busy === "run" ? "Reconciling…" : "Run reconciliation"}</button>}
            </div>
            {view.blockers.length > 0 && (
              <div className="panelBody"><p className="muted">Before this case can be reconciled:</p>
                <ul>{view.blockers.map((b) => <li key={b}>{b}</li>)}</ul></div>
            )}
            {run && summary && (
              <div className="panelBody">
                <section className="grid metrics">
                  <article className="card"><span>Records processed</span><strong>{run.run.recordsProcessed}</strong></article>
                  <article className="card"><span>Internal payments matched</span><strong>{run.run.internalMatched} of {run.run.internalPayments}</strong></article>
                  <article className="card"><span>Match rate</span><strong>{rate ?? "—"}</strong></article>
                  <article className={`card${run.run.exceptionCount > 0 ? " alert" : ""}`}><span>Exceptions</span><strong>{run.run.exceptionCount}</strong></article>
                  <article className="card"><span>Rejected input rows</span><strong>{run.run.rejectedInputs}</strong></article>
                  {/* One card per currency. A single total across currencies would be a number that means nothing. */}
                  {run.unresolvedByCurrency.map((t) => (
                    <article className="card alert" key={t.currency}><span>Unresolved at run · {t.currency}</span><strong>{money(t.unresolvedAmount, t.currency)}</strong></article>
                  ))}
                </section>
                <div className="split" style={{ marginTop: 18 }}>
                  <div><h3>Exceptions by type</h3>
                    {Object.keys(summary.exceptionsByType).length === 0 ? <p className="muted">None.</p> :
                      <ul>{Object.entries(summary.exceptionsByType).map(([t, n]) => (
                        <li key={t}><Link href={`/reconciliation?caseId=${caseId}&type=${t}`}>{words(t)}</Link> · {n}</li>))}</ul>}
                  </div>
                  <div><h3>Matched by rule</h3>
                    <ul>{Object.entries(summary.matchesByRule).map(([r, n]) => <li key={r}><span className="mono">{r}</span> · {n}</li>)}</ul>
                    <h3>Settlement coverage</h3>
                    <ul>
                      {summary.settlementCoveredProviders.map((p) => <li key={p}><span className="mono">{p}</span> · settlement file supplied</li>)}
                      {summary.providersWithoutSettlementFile.map((p) => <li key={p} className="error"><span className="mono">{p}</span> · no settlement file, settlement NOT checked</li>)}
                    </ul>
                  </div>
                </div>
                <p className="muted" style={{ fontSize: 12 }}>
                  Ruleset <span className="mono">{run.run.rulesetVersion}</span> · run key <span className="mono" title={run.run.runKey}>{run.run.runKey.slice(0, 16)}…</span> · completed {dateTime(run.run.completedAt)} · {summary.feesChecked} fee(s) checked against a schedule
                </p>
                <p><Link href={`/reconciliation?caseId=${caseId}`} className="btn">Work the {run.run.exceptionCount} exception(s) →</Link></p>
              </div>
            )}
            {!run && view.blockers.length === 0 && <EmptyState title="Not reconciled yet" hint="Run reconciliation to match the imported records and raise exceptions." />}
          </section>

          <section className="panel" style={{ marginTop: 18 }}>
            <div className="panelHeader"><div><h2>3 · Evidence bundle</h2><p className="sub">INTERIM while exceptions are open; FINAL once the case is closed. Operational evidence — not an audit opinion or a certification.</p></div></div>
            <div className="panelBody">
              <div className="row" style={{ gap: 8 }}>
                <button disabled={!run || busy !== null} onClick={() => act("bundle", async () => { setBundle(await api.exportReconBundle(caseId)); })}>
                  {busy === "bundle" ? "Exporting…" : "Export bundle"}</button>
                {!closed && <button className="secondary" disabled={!run || busy !== null}
                  onClick={() => act("close", async () => { await api.closeReconCase(caseId); return "Case closed. Its next bundle is FINAL."; })}>Close case</button>}
              </div>
              {bundle && (
                <div className="issue-facts" style={{ marginTop: 12 }}>
                  <div className="entry"><span className="muted">Status</span><span><StatusPill value={bundle.bundleStatus} /></span></div>
                  <div className="entry"><span className="muted">Content hash</span><span className="mono" style={{ wordBreak: "break-all" }}>{bundle.contentHash}</span></div>
                  <div className="entry"><span className="muted">File</span><span>{bytes(bundle.byteSize)} · {bundle.signed ? "signed" : "unsigned (no signing key configured)"}</span></div>
                  <div className="entry"><span className="muted">Verify offline</span><span className="mono">python3 scripts/verify_recon_bundle.py bundle.json</span></div>
                  <div className="entry"><span /><button className="secondary" onClick={download}>Download JSON</button></div>
                </div>
              )}
            </div>
          </section>
        </>
      )}
    </Shell>
  );
}
