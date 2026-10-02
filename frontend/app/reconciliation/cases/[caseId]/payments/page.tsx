"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { EmptyState, SeverityPill, StatusPill } from "../../../../components/ui";
import Shell from "../../../../components/Shell";
import { api } from "../../../../lib/api";
import { kindLabel, needsAttention, positions, roleLabel, roleTone, stateLabel, stateTone, utc, whenText } from "../../../../lib/timeline";
import type { PaymentTimelineView, ReconCase, TimelineAmount, TimelineEvent, TimelineFinding } from "../../../../lib/types";

function words(value: string): string {
  return value.replace(/_/g, " ").toLowerCase();
}

/**
 * One payment, as the case's evidence shows it. Everything on this page comes from the server's derived
 * view; the page adds no state of its own and totals nothing. Amounts are printed as exact text, one
 * currency at a time.
 */
export default function PaymentTimelinePage() {
  const { caseId } = useParams<{ caseId: string }>();
  const [ref, setRef] = useState<string | null>(null);
  const [provider, setProvider] = useState("");
  const [lookup, setLookup] = useState("");
  const [lookupProvider, setLookupProvider] = useState("");
  const [reconCase, setReconCase] = useState<ReconCase | null>(null);
  const [view, setView] = useState<PaymentTimelineView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    setRef(q.get("ref") ?? "");
    setProvider(q.get("provider") ?? "");
    setLookup(q.get("ref") ?? "");
    setLookupProvider(q.get("provider") ?? "");
  }, []);

  const load = useCallback(async () => {
    if (!caseId || ref === null) return;
    setError(null);
    try {
      setReconCase((await api.getReconCase(caseId)).reconciliationCase);
      if (!ref) {
        setView(null);
        return;
      }
      setLoading(true);
      setView(await api.paymentTimeline(caseId, ref, provider || undefined));
    } catch (e) {
      // A failed read shows the failure. It never leaves an earlier payment's timeline on screen.
      setView(null);
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [caseId, ref, provider]);

  useEffect(() => { void load(); }, [load]);

  function open(e: React.FormEvent) {
    e.preventDefault();
    const next = lookup.trim();
    if (!next) return;
    const q = new URLSearchParams({ ref: next });
    if (lookupProvider.trim()) q.set("provider", lookupProvider.trim());
    window.history.replaceState(null, "", `?${q.toString()}`);
    setProvider(lookupProvider.trim());
    setRef(next);
  }

  const at = view ? positions(view) : {};
  // Arriving from an exception, the reference is a 64-character record key. Show the payment's own name instead.
  const heading = ref && /^[0-9a-f]{64}$/.test(ref) && view
    ? view.payment.internalRefs[0] ?? view.payment.stableRefs[0] ?? ref : ref;
  const c = view?.conclusion;

  return (
    <Shell active="/reconciliation/cases">
      <header className="topbar">
        <div>
          <p className="eyebrow"><Link href="/reconciliation/cases">Cases</Link> / <Link href={`/reconciliation/cases/${caseId}`}>{reconCase?.caseRef ?? "…"}</Link> / payment</p>
          <h1>{ref ? <span className="mono" style={{ fontSize: "inherit" }}>{heading}</span> : "Payment timeline"}</h1>
          <p className="sub">What this case&apos;s evidence shows for one payment, in the order it happened. Read-only: nothing here moves money or changes a record.</p>
        </div>
      </header>

      <section className="panel">
        <form className="panelBody" onSubmit={open}>
          <div className="row">
            <label htmlFor="timeline-ref">Payment, transaction, event or internal reference<br />
              <input id="timeline-ref" value={lookup} onChange={(e) => setLookup(e.target.value)} placeholder="e.g. pb_tx_001" maxLength={160} style={{ minWidth: 280 }} required /></label>
            <label htmlFor="timeline-provider" title="Only needed when two providers use the same reference.">Provider (optional)<br />
              <input id="timeline-provider" value={lookupProvider} onChange={(e) => setLookupProvider(e.target.value)} placeholder="provider-b" /></label>
            <button disabled={!lookup.trim() || loading}>{loading ? "Reading…" : "Show timeline"}</button>
          </div>
        </form>
      </section>

      {error && <p className="error" role="alert">{error}</p>}
      {loading && !view && <div className="skeleton" style={{ maxWidth: 480, minHeight: 24, marginTop: 18 }} />}
      {!view && !error && !loading && ref === "" && (
        <EmptyState title="No payment selected" hint="Enter a reference from the internal ledger, a provider transaction, a provider event or a settlement line." />
      )}

      {view && c && (
        <>
          <section className="panel" style={{ marginTop: 18 }} aria-labelledby="conclusion-heading">
            <div className="panelHeader">
              <div>
                <h2 id="conclusion-heading">What the evidence supports</h2>
                <p className="sub">Derived from the items below on every read. It is never stored, and never says more than they do.</p>
              </div>
              <span className={`pill ${stateTone(c.state)}`}>{stateLabel(c.state)}</span>
            </div>
            <div className="panelBody">
              <p style={{ margin: 0, fontSize: 15 }}>{c.statement}</p>
              {c.openExposureByCurrency.length > 0 && (
                <div style={{ display: "flex", gap: 12, flexWrap: "wrap", marginTop: 14 }}>
                  {/* One card per currency. Amounts in different currencies are never added together. */}
                  {c.openExposureByCurrency.map((x) => (
                    <article className="card alert" key={x.currency} style={{ minWidth: 220 }}><span>At risk · {x.currency}</span><strong className="mono">{x.amount}</strong></article>
                  ))}
                </div>
              )}
              {c.notes.length > 0 && (
                <div className="notice warn" style={{ marginTop: 14 }}>
                  <b>What limits this statement</b>
                  <ul style={{ margin: "6px 0 0", paddingLeft: 18 }}>
                    {c.notes.map((n, i) => <li key={`${n.code}-${i}`}><span className="mono">{words(n.code)}</span> — {n.detail}</li>)}
                  </ul>
                </div>
              )}
              <p className="muted" style={{ fontSize: 12, marginBottom: 0 }}>
                {c.basis
                  ? <>Latest run <span className="mono" title={c.basis.runKey}>{c.basis.runKey.slice(0, 16)}…</span> · ruleset <span className="mono">{c.basis.rulesetVersion}</span> · completed {utc(c.basis.completedAt)}</>
                  : <>No reconciliation run exists for this case.</>}
                {" · "}providers: {view.payment.providers.length ? view.payment.providers.join(", ") : "—"}
                {" · "}currencies: {view.payment.currencies.join(", ") || "—"}
              </p>
            </div>
          </section>

          <section className="panel" style={{ marginTop: 18 }} aria-labelledby="timeline-heading">
            <div className="panelHeader"><div><h2 id="timeline-heading">Timeline · {view.timeline.length} item(s)</h2>
              <p className="sub">Ordered by when each thing happened at its source. Items whose time could not be established are listed last and say so.</p></div></div>
            <div className="panelBody">
              <ol className="timeline">
                {view.timeline.map((e) => <Item key={e.eventId} e={e} at={at} />)}
              </ol>
            </div>
          </section>

          <section className="panel" style={{ marginTop: 18 }} aria-labelledby="findings-heading">
            <div className="panelHeader"><div><h2 id="findings-heading">Findings · {view.findings.length}</h2>
              <p className="sub">Raised by the deterministic rules, with what people did about each. A finding stays here until someone decides it.</p></div></div>
            {view.findings.length === 0
              ? <EmptyState title="No finding cites this payment" hint="That is a statement about the rules that ran, not a guarantee. Check the limits listed above." />
              : view.findings.map((f) => <FindingCard key={f.exceptionId} f={f} at={at} />)}
          </section>
        </>
      )}
    </Shell>
  );
}

function Amounts({ amounts }: { amounts: TimelineAmount[] }) {
  if (amounts.length === 0) return <span className="muted">no amount on this item</span>;
  return (
    <>
      {amounts.map((a, n) => (
        <div key={n} className="mono">
          <span className="muted">{words(a.role)}</span> {a.currency} {a.rawValue ?? a.value}
          {a.direction && a.direction !== "AS_SIGNED" && <span className="muted"> · {words(a.direction)}</span>}
          {a.providerField && <span className="muted"> · {a.providerField}</span>}
        </div>
      ))}
    </>
  );
}

function Item({ e, at }: { e: TimelineEvent; at: Record<string, number> }) {
  const ev = e.evidence;
  return (
    <li id={`item-${e.position}`} className={needsAttention(e) ? "hot" : undefined}>
      <div className="when mono">#{e.position} · {whenText(e)}{e.receivedAt && <> · received {utc(e.receivedAt)}</>}</div>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center", margin: "4px 0" }}>
        <b>{kindLabel(e.kind)}</b>
        {e.eventType && <span className="mono">{e.eventType}</span>}
        {e.status && <StatusPill value={e.status} />}
        <span className={`pill ${roleTone(e.role)}`}>{roleLabel(e.role)}</span>
        {e.arrivedOutOfOrder && <span className="pill warn">arrived after a later event</span>}
        {ev.deliveryCount > 1 && <span className="pill info">delivered {ev.deliveryCount}× · counted once</span>}
        {!ev.intact && <span className="pill bad">row does not match its hash</span>}
      </div>
      <div className="muted" style={{ fontSize: 12.5 }}>
        {e.provider && <>{e.provider} · </>}
        {e.refs.stableRef && <>transaction <span className="mono">{e.refs.stableRef}</span> · </>}
        {e.refs.internalRef && <>internal <span className="mono">{e.refs.internalRef}</span> · </>}
        {e.refs.providerEventId && <>event <span className="mono">{e.refs.providerEventId}</span> · </>}
        {e.refs.settlementBatch && <>batch <span className="mono">{e.refs.settlementBatch}</span> · </>}
        joined by {words(e.linkedBy ?? "reference")}
      </div>
      <div style={{ margin: "6px 0" }}><Amounts amounts={e.amounts} />
        {e.fxRate && <div className="mono muted">provider rate {e.fxRate} · kept as evidence, not applied</div>}</div>
      {e.roleReason && <div className="muted" style={{ fontSize: 12.5 }}>{e.roleReason}</div>}
      {e.duplicateOf && at[e.duplicateOf] && <div style={{ fontSize: 12.5 }}>Duplicate of <a href={`#item-${at[e.duplicateOf]}`}>#{at[e.duplicateOf]}</a></div>}
      {e.derivedInto && at[e.derivedInto] && <div style={{ fontSize: 12.5 }}>Source row of <a href={`#item-${at[e.derivedInto]}`}>#{at[e.derivedInto]}</a></div>}
      {e.matches.length > 0 && (
        <div style={{ fontSize: 12.5 }}>Matched to {e.matches.map((m, n) => (
          <span key={n}>{n > 0 && ", "}<a href={`#item-${at[m.counterpartEventId]}`}>#{at[m.counterpartEventId]}</a> <span className="muted mono">{m.ruleId}</span></span>))}</div>
      )}
      {e.findingIds.length > 0 && (
        <div style={{ fontSize: 12.5 }}>Cited by {e.findingIds.map((id, n) => (
          <span key={id}>{n > 0 && ", "}<a href={`#finding-${id}`}>finding {id.slice(0, 8)}</a></span>))}</div>
      )}
      <details style={{ marginTop: 6 }}>
        <summary className="muted" style={{ fontSize: 12.5, cursor: "pointer" }}>
          Source: {ev.filename ?? "unknown file"} · row {ev.rowNumber}{!ev.inLatestRun && " · not in the latest run"}
        </summary>
        <div className="issue-facts" style={{ marginTop: 8 }}>
          <div className="entry"><span className="muted">File</span><span>{ev.filename} · {words(ev.sourceType ?? "")} · <span className="mono">{ev.sourceIdentity}</span> · {ev.profile}</span></div>
          <div className="entry"><span className="muted">File SHA-256</span><span className="mono" style={{ wordBreak: "break-all" }}>{ev.fileSha256}</span></div>
          <div className="entry"><span className="muted">Row SHA-256</span><span className="mono" style={{ wordBreak: "break-all" }}>{ev.rowSha256} · {ev.intact ? "matches the stored row" : "DOES NOT match the stored row"}</span></div>
          <div className="entry"><span className="muted">Imported</span><span>{ev.importedAt ? utc(ev.importedAt) : "—"}{ev.feedId && " · live event feed"}</span></div>
        </div>
        <pre className="mono" style={{ whiteSpace: "pre-wrap", wordBreak: "break-all", margin: "8px 0 0", fontSize: 12 }}>{ev.rawRow}</pre>
      </details>
    </li>
  );
}

function FindingCard({ f, at }: { f: TimelineFinding; at: Record<string, number> }) {
  return (
    <article id={`finding-${f.exceptionId}`} className="panelBody" style={{ borderTop: "1px solid var(--line)" }}>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}>
        <b>{words(f.type)}</b>
        <SeverityPill value={f.severity} />
        <StatusPill value={f.lifecycleState} />
        {f.classification === "UNKNOWN" && <span className="pill warn">outcome not known</span>}
        {!f.raisedByLatestRun && <span className="pill info">raised by an earlier run</span>}
        <Link href={`/reconciliation/${f.exceptionId}`}>{f.open ? "work this exception →" : "open exception →"}</Link>
      </div>
      {f.explanation && <p style={{ margin: "8px 0" }}>{f.explanation}</p>}
      <div className="issue-facts">
        <div className="entry"><span className="muted">Expected</span><span className="mono">{f.expected ?? "—"}</span></div>
        <div className="entry"><span className="muted">Actual</span><span className="mono">{f.actual ?? "—"}</span></div>
        <div className="entry"><span className="muted">Money at risk</span>
          <span className="mono">{f.exposureAmount === null ? "no amount applies" : `${f.exposureCurrency} ${f.exposureAmount}`}</span></div>
        <div className="entry"><span className="muted">Rule</span><span className="mono">{f.ruleId ?? "—"} · {f.ruleVersion ?? "—"}</span></div>
        <div className="entry"><span className="muted">Items cited</span>
          <span>{f.eventIds.filter((id) => at[id]).map((id, n) => <span key={id}>{n > 0 && ", "}<a href={`#item-${at[id]}`}>#{at[id]}</a></span>)}</span></div>
        <div className="entry"><span className="muted">Raised</span><span>{utc(f.raisedAt)}{f.dueAt && f.open && <> · due {utc(f.dueAt)}</>}</span></div>
        {f.decision && (
          <div className="entry"><span className="muted">Decision</span>
            <span>{words(f.decision.closedAs)} · <span className="mono">{f.decision.reasonCode}</span>{f.decision.explanation && <> — {f.decision.explanation}</>}
              {f.decision.decidedAt && <span className="muted"> · {utc(f.decision.decidedAt)}</span>}</span></div>
        )}
      </div>
      {f.history.length > 0 && (
        <ul className="timeline" style={{ marginTop: 12 }}>
          {f.history.map((h) => (
            <li key={h.seq}>
              <div className="when mono">{h.at ? utc(h.at) : "—"}</div>
              <div><b>{words(h.kind)}</b>{h.toState && h.fromState !== h.toState && <span className="muted"> → {words(h.toState)}</span>}
                {h.body && <> — {h.body}</>}
                {h.evidenceFilename && <span className="muted mono"> · {h.evidenceFilename} {h.evidenceSha256?.slice(0, 12)}…</span>}</div>
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}
