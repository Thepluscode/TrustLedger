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
        (l.resourceId ?? "").toLowerCase().includes(q),
    );
  }, [logs, filter]);

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
            placeholder="action, resource, actor…"
          />
        </div>
      </header>
      {error && <p className="error">{error}</p>}

      <section className="panel">
        <table>
          <thead>
            <tr>
              <th>When</th>
              <th>Actor</th>
              <th>Action</th>
              <th>Resource</th>
              <th>Resource ID</th>
            </tr>
          </thead>
          <tbody>
            {filtered === null && <SkeletonRows cols={5} rows={6} />}
            {filtered?.map((l) => (
              <tr key={l.id}>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(l.createdAt)}</td>
                <td>
                  <span className="pill info">{l.actorType}</span>
                </td>
                <td>{l.action.replace(/_/g, " ").toLowerCase()}</td>
                <td className="muted">{l.resourceType.replace(/_/g, " ").toLowerCase()}</td>
                <td className="mono">{l.resourceId ? shortId(l.resourceId) : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
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
