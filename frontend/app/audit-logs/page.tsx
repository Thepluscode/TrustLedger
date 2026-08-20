"use client";

import { useEffect, useMemo, useState } from "react";
import Shell from "../components/Shell";
import { EmptyState, SkeletonRows } from "../components/ui";
import { api } from "../lib/api";
import { dateTime, shortId } from "../lib/format";
import type { AuditLogView } from "../lib/types";

/** Audit logs (design.md §16) — newest-first, filterable by action/resource text. */
export default function AuditLogsPage() {
  const [logs, setLogs] = useState<AuditLogView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState("");

  useEffect(() => {
    api.listAuditLogs().then(setLogs).catch((e) => setError((e as Error).message));
  }, []);

  const filtered = useMemo(() => {
    if (!logs) return null;
    const q = filter.trim().toLowerCase();
    if (!q) return logs;
    return logs.filter(
      (l) =>
        l.action.toLowerCase().includes(q) ||
        l.resourceType.toLowerCase().includes(q) ||
        l.actorType.toLowerCase().includes(q) ||
        (l.resourceId ?? "").toLowerCase().includes(q) ||
        (l.correlationId ?? "").toLowerCase().includes(q),
    );
  }, [logs, filter]);

  const humanActors = logs?.filter((l) => l.actorType === "USER").length ?? 0;
  const correlated = logs?.filter((l) => l.correlationId).length ?? 0;

  return (
    <Shell active="/audit-logs">
      <header className="topbar">
        <div>
          <p className="eyebrow">Compliance</p>
          <h1>Audit logs</h1>
          <p className="sub">Every sensitive action is recorded. Showing the latest 200 entries for this tenant.</p>
        </div>
        <div style={{ minWidth: 260 }}>
          <label htmlFor="q" style={{ marginTop: 0 }}>Filter</label>
          <input
            id="q"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder="action, resource, actor, request id…"
          />
        </div>
      </header>
      {error && <p className="error">{error}</p>}

      <section className="operations-strip compliance-strip" aria-label="Audit register summary">
        <article><small>Entries loaded</small><strong>{logs?.length ?? "—"}</strong><span>Latest tenant events</span></article>
        <article><small>User actions</small><strong>{logs ? humanActors : "—"}</strong><span>Actor-attributed records</span></article>
        <article><small>Request-linked</small><strong>{logs ? correlated : "—"}</strong><span>Correlation ID available</span></article>
      </section>

      <section className="panel audit-register">
        <div className="panelHeader"><div><h2>Activity register</h2><p className="sub">Newest first. IDs remain available for incident and support correlation.</p></div><span className="muted">{filtered?.length ?? 0} shown</span></div>
        <table className="desktop-table">
          <thead>
            <tr>
              <th>When</th>
              <th>Actor</th>
              <th>Action</th>
              <th>Resource</th>
              <th>Resource ID</th>
              <th>Request ID</th>
            </tr>
          </thead>
          <tbody>
            {filtered === null && <SkeletonRows cols={6} rows={6} />}
            {filtered?.map((l) => (
              <tr key={l.id}>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(l.createdAt)}</td>
                <td>
                  <span className="pill info">{l.actorType}</span>
                </td>
                <td>{l.action.replace(/_/g, " ").toLowerCase()}</td>
                <td className="muted">{l.resourceType.replace(/_/g, " ").toLowerCase()}</td>
                <td className="mono">{l.resourceId ? shortId(l.resourceId) : "—"}</td>
                {/* Quoted verbatim in a support ticket, and greppable in logs — so full value on
                    hover, and an em dash for rows written off-request by a worker. */}
                <td className="mono" title={l.correlationId ?? "written off-request (no correlation id)"}>
                  {l.correlationId ? shortId(l.correlationId) : "—"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="mobile-record-list">
          {filtered?.map((l) => (
            <article className="mobile-record audit-record" key={l.id}>
              <div className="record-head"><div><small>{dateTime(l.createdAt)}</small><b>{l.action.replace(/_/g, " ").toLowerCase()}</b></div><span className="pill info">{l.actorType}</span></div>
              <div className="record-stats">
                <span><small>Resource</small><b>{l.resourceType.replace(/_/g, " ").toLowerCase()}</b></span>
                <span><small>Resource ID</small><b className="mono">{l.resourceId ? shortId(l.resourceId) : "—"}</b></span>
                <span><small>Request ID</small><b className="mono">{l.correlationId ? shortId(l.correlationId) : "—"}</b></span>
              </div>
            </article>
          ))}
        </div>
        {filtered !== null && filtered.length === 0 && (
          <EmptyState
            title={filter ? "No entries match that filter" : "No audit entries yet"}
            hint={
              filter
                ? "Try a broader term — actions are recorded as e.g. transfer created, case approved, evidence exported."
                : "Sensitive actions (transfers, case decisions, evidence exports, policy changes) appear here as they happen."
            }
          />
        )}
      </section>
    </Shell>
  );
}
