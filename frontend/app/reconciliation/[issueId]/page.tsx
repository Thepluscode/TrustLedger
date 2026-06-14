"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ConfirmModal, SeverityPill, StatusPill } from "../../components/ui";
import Shell from "../../components/Shell";
import { api } from "../../lib/api";
import { dateTime, shortId } from "../../lib/format";
import type { ReconciliationIssue } from "../../lib/types";

function pretty(json: string | null): string {
  if (!json) return "—";
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}

export default function ReconciliationIssuePage() {
  const params = useParams<{ issueId: string }>();
  const id = params.issueId;
  const [issue, setIssue] = useState<ReconciliationIssue | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (id) api.getReconciliationIssue(id).then(setIssue).catch((e) => setError((e as Error).message));
  }, [id]);

  async function resolve() {
    if (!id) return;
    setBusy(true);
    setError(null);
    try {
      setIssue(await api.resolveReconciliationIssue(id));
      setConfirm(false);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Shell active="/reconciliation">
      <header className="topbar">
        <div>
          <p className="eyebrow"><Link href="/reconciliation">Reconciliation</Link> / issue</p>
          <h1>Issue {shortId(id)}</h1>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {!issue && !error && <div className="skeleton" style={{ maxWidth: 480, minHeight: 24 }} />}

      {issue && (
        <>
          <section className="panel">
            <div className="panelBody">
              <p className="row" style={{ gap: 10, alignItems: "center" }}>
                <SeverityPill value={issue.severity} /> <StatusPill value={issue.status} />
                <span className="muted">{issue.type.replace(/_/g, " ").toLowerCase()}</span>
              </p>
              <div style={{ maxWidth: 560 }}>
                <div className="entry"><span className="muted">Affected entity</span><span><span className="mono">{shortId(issue.entityId)}</span> {issue.entityType.replace(/_/g, " ").toLowerCase()}</span></div>
                <div className="entry"><span className="muted">Created</span><span>{dateTime(issue.createdAt)}</span></div>
                <div className="entry"><span className="muted">Resolved</span><span>{issue.resolvedAt ? dateTime(issue.resolvedAt) : "—"}</span></div>
              </div>
              {issue.status === "OPEN" && (
                <div className="row" style={{ marginTop: 16 }}>
                  <button onClick={() => setConfirm(true)}>Resolve issue</button>
                </div>
              )}
            </div>
          </section>

          <section className="panel" style={{ marginTop: 18 }}>
            <div className="panelHeader"><div><h2>Expected vs actual</h2></div></div>
            <div className="split">
              <div><h3>Expected</h3><pre className="mono" style={{ whiteSpace: "pre-wrap", margin: 0 }}>{issue.expectedState ?? "—"}</pre></div>
              <div><h3>Actual</h3><pre className="mono" style={{ whiteSpace: "pre-wrap", margin: 0 }}>{issue.actualState ?? "—"}</pre></div>
            </div>
          </section>

          <section className="panel" style={{ marginTop: 18 }}>
            <div className="panelHeader"><div><h2>Evidence</h2><p className="sub">The data the worker captured when it raised this issue.</p></div></div>
            <div className="panelBody">
              <pre className="mono" style={{ whiteSpace: "pre-wrap", margin: 0, fontSize: 12 }}>{pretty(issue.evidence)}</pre>
            </div>
          </section>
        </>
      )}

      <ConfirmModal
        open={confirm}
        title="Resolve reconciliation issue"
        body="Mark this issue resolved. This is recorded in the audit log; resolve only once the underlying mismatch is actually fixed."
        confirmWord="RESOLVE"
        confirmLabel="Resolve issue"
        busy={busy}
        onConfirm={resolve}
        onCancel={() => setConfirm(false)}
      />
    </Shell>
  );
}
