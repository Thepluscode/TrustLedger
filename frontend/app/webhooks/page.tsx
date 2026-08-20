"use client";

import { useEffect, useState } from "react";
import { EmptyState, SkeletonRows, StatusPill } from "../components/ui";
import Shell from "../components/Shell";
import { api } from "../lib/api";
import { dateTime } from "../lib/format";
import type { WebhookEvent } from "../lib/types";

export default function WebhooksPage() {
  const [events, setEvents] = useState<WebhookEvent[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  useEffect(() => {
    api.listWebhookEvents()
      .then((items) => {
        setEvents(items);
        setOpen((current) => current ?? items[0]?.id ?? null);
      })
      .catch((e) => setError((e as Error).message));
  }, []);

  const selectedEvent = events?.find((event) => event.id === open) ?? null;
  const validSignatures = events?.filter((event) => event.signatureValid).length ?? 0;
  const processedEvents = events?.filter((event) => event.processed).length ?? 0;

  return (
    <Shell active="/webhooks">
      <header className="topbar">
        <div>
          <p className="eyebrow">Payment Rails</p>
          <h1>Webhook events</h1>
          <p className="sub">Inbound provider callbacks, signature-checked and applied at most once.</p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      <div className="safety-boundary webhook-boundary">
        <strong>Replay-safe ingest</strong>
        <span>Provider + event ID is the deduplication key. A replay creates no second inbox row and no second ledger posting.</span>
      </div>

      <section className="operations-strip" aria-label="Webhook event summary">
        <div><span>Received</span><strong>{events?.length ?? "—"}</strong></div>
        <div><span>Valid signatures</span><strong>{events ? validSignatures : "—"}</strong></div>
        <div><span>Processed</span><strong>{events ? processedEvents : "—"}</strong></div>
        <div><span>Awaiting processing</span><strong>{events ? events.length - processedEvents : "—"}</strong></div>
      </section>

      <div className={`webhook-workspace${selectedEvent ? " has-selection" : ""}`}>
        <section className="panel webhook-register">
          <div className="panelHeader"><div><h2>Accepted events</h2><p className="sub">This register contains the unique, accepted inbox records.</p></div></div>
          <table className="desktop-table">
            <thead><tr><th>Event</th><th>Type</th><th>Provider reference</th><th>Signature</th><th>Processed</th><th>Received</th><th></th></tr></thead>
            <tbody>
              {events === null && <SkeletonRows cols={7} />}
              {events?.map((event) => (
                <tr className={open === event.id ? "selected-row" : ""} key={event.id}>
                  <td className="mono">{event.eventId}</td>
                  <td>{event.eventType}</td>
                  <td className="mono muted">{event.providerReference}</td>
                  <td>{event.signatureValid ? "Valid" : "Invalid"}</td>
                  <td><StatusPill value={event.processed ? "COMPLETED" : "PENDING"} /></td>
                  <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(event.createdAt)}</td>
                  <td><button className="ghost" onClick={() => setOpen(event.id)}>Inspect</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="mobile-record-list">
            {events?.map((event) => (
              <button className="mobile-record webhook-record" key={event.id} onClick={() => setOpen(open === event.id ? null : event.id)}>
                <div className="record-head"><div><small>{event.provider}</small><strong className="mono">{event.eventId}</strong></div><StatusPill value={event.processed ? "COMPLETED" : "PENDING"} /></div>
                <div className="record-stats"><span><small>Type</small><b>{event.eventType}</b></span><span><small>Signature</small><b>{event.signatureValid ? "Valid" : "Invalid"}</b></span></div>
                <small>{dateTime(event.createdAt)} · ref {event.providerReference}</small>
                {open === event.id && <pre className="payload-code">{prettyPayload(event.payload)}</pre>}
              </button>
            ))}
          </div>
          {events !== null && events.length === 0 && <EmptyState title="No webhook events yet" hint="Submit an external payment through the sandbox rail to create a callback record." />}
        </section>

        {selectedEvent && (
          <aside className="panel payload-inspector" aria-label="Webhook payload">
            <div className="panelHeader"><div><h2>Payload</h2><p className="mono sub">{selectedEvent.eventId}</p></div><button className="ghost" onClick={() => setOpen(null)}>Close</button></div>
            <dl className="payload-facts"><div><dt>Provider</dt><dd>{selectedEvent.provider}</dd></div><div><dt>Event type</dt><dd>{selectedEvent.eventType}</dd></div><div><dt>Provider ref</dt><dd className="mono">{selectedEvent.providerReference}</dd></div><div><dt>Received</dt><dd>{dateTime(selectedEvent.createdAt)}</dd></div></dl>
            <pre className="payload-code">{prettyPayload(selectedEvent.payload)}</pre>
          </aside>
        )}
      </div>
    </Shell>
  );
}

function prettyPayload(payload: string): string {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2);
  } catch {
    return payload;
  }
}
