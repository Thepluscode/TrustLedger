"use client";

import { useEffect, useState, type ReactNode } from "react";
import Shell from "../components/Shell";
import { api } from "../lib/api";
import { dateTime } from "../lib/format";
import type { MonitoringSnapshot } from "../lib/types";

// Map a component status to the existing pill tone (OK→green, WARN→amber, CRITICAL→red).
const TONE: Record<string, string> = { OK: "ok", WARN: "warn", CRITICAL: "failed" };

function Status({ value }: { value: string }) {
  return <span className={`pill ${TONE[value] ?? "info"}`}>{value}</span>;
}

function num(v: number | null | undefined, suffix = "") {
  return v === null || v === undefined ? "—" : `${v}${suffix}`;
}

function Card({ title, status, children }: { title: string; status: string; children: ReactNode }) {
  return (
    <section className="panel" style={{ margin: 0 }}>
      <div className="panelHeader">
        <div><h2 style={{ fontSize: "1rem" }}>{title}</h2></div>
        <Status value={status} />
      </div>
      <div className="panelBody">{children}</div>
    </section>
  );
}

function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div style={{ display: "flex", justifyContent: "space-between", padding: "4px 0" }}>
      <span className="muted">{label}</span>
      <span className="mono">{value}</span>
    </div>
  );
}

export default function MonitoringPage() {
  const [snap, setSnap] = useState<MonitoringSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [fetchedAt, setFetchedAt] = useState<string | null>(null);

  function load() {
    setLoading(true);
    setError(null);
    api.getMonitoring()
      .then((s) => { setSnap(s); setFetchedAt(new Date().toISOString()); })
      .catch((e) => setError((e as Error).message))
      .finally(() => setLoading(false));
  }
  useEffect(load, []);

  return (
    <Shell active="/monitoring">
      <header className="topbar">
        <div>
          <p className="eyebrow">Developer</p>
          <h1>Monitoring</h1>
          <p className="sub">
            Live operational health — derived from real system state (DB probe, request-latency timers,
            tenant-scoped table counts, Postgres lock waits). Point-in-time; refresh to re-read.
          </p>
        </div>
        <button className="secondary" onClick={load} disabled={loading}>{loading ? "Refreshing…" : "Refresh"}</button>
      </header>
      {error && <p className="error">{error}</p>}

      {snap && (
        <>
          <div
            className={`notice ${snap.overallStatus === "OK" ? "ok" : snap.overallStatus === "WARN" ? "warn" : "danger"}`}
            style={{ display: "flex", alignItems: "center", gap: 12 }}
          >
            <Status value={snap.overallStatus} />
            <b>{snap.banner}</b>
            {fetchedAt && <span className="muted" style={{ marginLeft: "auto" }}>as of {dateTime(fetchedAt)}</span>}
          </div>

          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))", gap: 16, marginTop: 18 }}>
            <Card title="API / database" status={snap.database.status}>
              <Stat label="Reachable" value={snap.database.up ? "yes" : "no"} />
              <Stat label="Probe latency" value={num(snap.database.latencyMs, " ms")} />
            </Card>

            <Card title="Transfer latency" status={snap.transferLatency.status}>
              <Stat label="Endpoint" value={snap.transferLatency.endpoint} />
              <Stat label="Samples" value={snap.transferLatency.samples} />
              <Stat label="Mean" value={num(snap.transferLatency.meanMs, " ms")} />
              <Stat label="Max" value={num(snap.transferLatency.maxMs, " ms")} />
            </Card>

            <Card title="Fraud scoring latency" status={snap.fraudScoringLatency.status}>
              <Stat label="Endpoint" value={snap.fraudScoringLatency.endpoint} />
              <Stat label="Samples" value={snap.fraudScoringLatency.samples} />
              <Stat label="Mean" value={num(snap.fraudScoringLatency.meanMs, " ms")} />
              <Stat label="Max" value={num(snap.fraudScoringLatency.maxMs, " ms")} />
            </Card>

            <Card title="Outbox lag" status={snap.outbox.status}>
              <Stat label="Pending events" value={snap.outbox.pending} />
              <Stat label="Oldest pending" value={num(snap.outbox.oldestPendingAgeSeconds, " s")} />
            </Card>

            <Card title="Webhook delivery" status={snap.webhooks.status}>
              <Stat label="Total received" value={snap.webhooks.total} />
              <Stat label="Invalid signature" value={snap.webhooks.invalidSignature} />
              <Stat label="Unprocessed" value={snap.webhooks.unprocessed} />
              <Stat label="Failure rate" value={`${snap.webhooks.failureRatePct}%`} />
            </Card>

            <Card title="Reconciliation" status={snap.reconciliation.status}>
              <Stat label="Open issues" value={snap.reconciliation.openIssues} />
              <Stat label="Last issue" value={snap.reconciliation.lastIssueAt ? dateTime(snap.reconciliation.lastIssueAt) : "none"} />
            </Card>

            <Card title="Provider confirmation" status={snap.payments.status}>
              <Stat label="Awaiting confirmation" value={snap.payments.awaitingProviderConfirmation} />
            </Card>

            <Card title="DB lock wait" status={snap.dbLockWait.status}>
              <Stat label="Waiting locks" value={snap.dbLockWait.waitingLocks} />
            </Card>

            <Card title="Certification coverage" status={snap.certifications.status}>
              <Stat label="Production configs" value={snap.certifications.productionConfigs} />
              <Stat label="Certified" value={snap.certifications.certified} />
              <Stat label="Expiring soon" value={snap.certifications.expiringSoon} />
              <Stat label="Uncertified" value={snap.certifications.uncertified} />
            </Card>
          </div>
        </>
      )}
    </Shell>
  );
}
