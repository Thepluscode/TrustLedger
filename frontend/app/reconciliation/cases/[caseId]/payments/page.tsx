"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { EmptyState } from "../../../../components/ui";
import { PaymentTimelineView } from "../../../../components/PaymentTimeline";
import Shell from "../../../../components/Shell";
import { api } from "../../../../lib/api";
import type { PaymentTimelineView as PaymentTimelineData, ReconCase } from "../../../../lib/types";

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
  const [view, setView] = useState<PaymentTimelineData | null>(null);
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

  // Arriving from an exception, the reference is a 64-character record key. Show the payment's own name instead.
  const heading = ref && /^[0-9a-f]{64}$/.test(ref) && view
    ? view.payment.internalRefs[0] ?? view.payment.stableRefs[0] ?? ref : ref;

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

      {view && (
        <>
          <PaymentTimelineView view={view} />
        </>
      )}
    </Shell>
  );
}
