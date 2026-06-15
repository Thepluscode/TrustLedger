"use client";

import { Fragment, useEffect, useState } from "react";
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
    api.listWebhookEvents().then(setEvents).catch((e) => setError((e as Error).message));
  }, []);

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
      <div className="notice">
        Events are deduped by provider + event id at ingest — a replayed callback is rejected with{" "}
        <b>no second row and no double ledger posting</b>. This list is the already-deduplicated set.
      </div>

      <section className="panel">
        <table>
          <thead>
            <tr><th>Event</th><th>Type</th><th>Provider ref</th><th>Signature</th><th>Processed</th><th>Created</th><th></th></tr>
          </thead>
          <tbody>
            {events === null && <SkeletonRows cols={7} />}
            {events?.map((e) => (
              <Fragment key={e.id}>
                <tr>
                  <td className="mono">{e.eventId}</td>
                  <td>{e.eventType}</td>
                  <td className="mono muted">{e.providerReference}</td>
                  <td><span className={`pill ${e.signatureValid ? "ok" : "bad"}`}>{e.signatureValid ? "valid" : "invalid"}</span></td>
                  <td><StatusPill value={e.processed ? "COMPLETED" : "PENDING"} /></td>
                  <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(e.createdAt)}</td>
                  <td><button className="ghost" onClick={() => setOpen(open === e.id ? null : e.id)}>{open === e.id ? "Hide" : "Payload"}</button></td>
                </tr>
                {open === e.id && (
                  <tr>
                    <td colSpan={7}>
                      <pre className="mono" style={{ whiteSpace: "pre-wrap", margin: 0, fontSize: 12 }}>{prettyPayload(e.payload)}</pre>
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
        {events !== null && events.length === 0 && (
          <EmptyState
            title="No webhook events yet"
            hint="Submit an external payment (sandbox rail) and the provider's settle/fail callbacks will appear here."
          />
        )}
      </section>
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
