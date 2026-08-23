"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ConfirmModal, SeverityPill, StatusPill } from "../../components/ui";
import Shell from "../../components/Shell";
import { api, getSession } from "../../lib/api";
import { dateTime, money, shortId } from "../../lib/format";
import type { ReconciliationAuditEntry, ReconciliationIssue, TeamMember } from "../../lib/types";

function pretty(json: string | null): string {
  if (!json) return "—";
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}

function resolutionFields(metadata: string): { outcome?: string; note?: string } {
  try {
    const m = JSON.parse(metadata) as { outcome?: string; note?: string };
    return { outcome: m.outcome, note: m.note };
  } catch {
    return {};
  }
}

/** The owner an assignment event moved the case to; "null" is the string the API writes when unassigning. */
function assignedTo(metadata: string): string | null {
  try {
    const m = JSON.parse(metadata) as { ownerEmail?: string };
    return m.ownerEmail && m.ownerEmail !== "null" ? m.ownerEmail : null;
  } catch {
    return null;
  }
}

/** The settlement statement a break came from, if any: the entity itself for a statement-level break,
 *  else the statementId stamped into the evidence for a line/attempt break. */
function sourceStatementId(issue: ReconciliationIssue): string | null {
  if (issue.entityType === "SETTLEMENT_STATEMENT") return issue.entityId;
  try {
    return (JSON.parse(issue.evidence) as { statementId?: string })?.statementId ?? null;
  } catch {
    return null;
  }
}

/** A resolved case is never late, however long it sat open before someone closed it. */
function overdue(issue: ReconciliationIssue): boolean {
  return issue.status === "OPEN" && new Date(issue.dueAt).getTime() < Date.now();
}

export default function ReconciliationIssuePage() {
  const params = useParams<{ issueId: string }>();
  const id = params.issueId;
  const [issue, setIssue] = useState<ReconciliationIssue | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState("");
  const [note, setNote] = useState("");
  const [audit, setAudit] = useState<ReconciliationAuditEntry[]>([]);
  const [auditError, setAuditError] = useState<string | null>(null);
  const [members, setMembers] = useState<TeamMember[]>([]);
  const [ownerChoice, setOwnerChoice] = useState("");
  const role = getSession()?.role.toUpperCase() ?? "";
  const canManage = role === "OWNER" || role === "ADMIN" || role === "TENANT_ADMIN";

  async function loadAudit() {
    if (!id) return;
    try {
      setAudit(await api.reconciliationIssueAudit(id));
      setAuditError(null);
    } catch (e) {
      setAuditError(`Activity unavailable: ${(e as Error).message}`);
    }
  }

  useEffect(() => {
    if (!id) return;
    api.getReconciliationIssue(id)
      .then((loaded) => {
        setIssue(loaded);
        setOwnerChoice(loaded.ownerUserId ?? "");
      })
      .catch((e) => setError((e as Error).message));
    void loadAudit();
    if (canManage) {
      api.listUsers().then(setMembers).catch((e) => setError(`Unable to load assignable owners: ${(e as Error).message}`));
    }
  }, [canManage, id]);

  async function assign() {
    if (!id) return;
    setBusy(true);
    setError(null);
    try {
      setIssue(await api.assignReconciliationIssue(id, ownerChoice || null));
      await loadAudit();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function resolve() {
    if (!id) return;
    setBusy(true);
    setError(null);
    try {
      setIssue(await api.resolveReconciliationIssue(id, outcome, note));
      setConfirm(false);
      loadAudit();
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
          <section className="panel reconciliation-issue-hero">
            <div className="panelBody">
              <p className="row" style={{ gap: 10, alignItems: "center" }}>
                <SeverityPill value={issue.severity} /> <StatusPill value={issue.status} />
                <span className="muted">{issue.type.replace(/_/g, " ").toLowerCase()}</span>
              </p>
              <div className="issue-facts">
                <div className="entry"><span className="muted">Affected entity</span><span><span className="mono">{shortId(issue.entityId)}</span> {issue.entityType.replace(/_/g, " ").toLowerCase()}</span></div>
                {sourceStatementId(issue) && (
                  <div className="entry"><span className="muted">Source statement</span>
                    <Link href={`/reconciliation/statements/${sourceStatementId(issue)}`}>view statement</Link></div>
                )}
                <div className="entry"><span className="muted">Money at risk</span>
                  <span className="mono">
                    {issue.exposureAmount && issue.exposureCurrency
                      ? money(issue.exposureAmount, issue.exposureCurrency)
                      : "— no amount applies"}
                  </span></div>
                <div className="entry"><span className="muted">Owner</span>
                  <span>{issue.ownerUserId
                    ? members.find((member) => member.id === issue.ownerUserId)?.email ?? <span className="mono">{shortId(issue.ownerUserId)}</span>
                    : "unassigned"}</span></div>
                <div className="entry"><span className="muted">Due</span>
                  <span className={overdue(issue) ? "error" : undefined}>
                    {dateTime(issue.dueAt)}{overdue(issue) ? " · overdue" : ""}
                  </span></div>
                <div className="entry"><span className="muted">Created</span><span>{dateTime(issue.createdAt)}</span></div>
                <div className="entry"><span className="muted">Resolved</span><span>{issue.resolvedAt ? dateTime(issue.resolvedAt) : "—"}</span></div>
              </div>
              {issue.status === "OPEN" && canManage && (
                <div className="resolution-form">
                  <label className="muted" htmlFor="issue-owner" style={{ display: "block", marginBottom: 6 }}>Accountable owner</label>
                  <div className="row">
                    <select id="issue-owner" value={ownerChoice} onChange={(e) => setOwnerChoice(e.target.value)} style={{ flex: 1 }}>
                      <option value="">Unassigned</option>
                      {members.map((member) => <option key={member.id} value={member.id}>{member.email} · {member.role.toLowerCase()}</option>)}
                    </select>
                    <button className="secondary" disabled={busy || ownerChoice === (issue.ownerUserId ?? "")} onClick={assign}>
                      {busy ? "Saving…" : "Assign owner"}
                    </button>
                  </div>
                </div>
              )}
              {issue.status === "OPEN" && canManage && (
                <div className="resolution-form">
                  <label className="muted" style={{ display: "block", marginBottom: 6 }}>Resolution outcome</label>
                  <select value={outcome} onChange={(e) => setOutcome(e.target.value)} style={{ width: "100%", marginBottom: 12 }}>
                    <option value="">Select an outcome…</option>
                    <option value="RECOVERED">Recovered — funds landed</option>
                    <option value="WRITTEN_OFF">Written off — unrecoverable</option>
                    <option value="FALSE_POSITIVE">False positive — no real break</option>
                    <option value="PROVIDER_CORRECTED">Provider corrected</option>
                    <option value="DUPLICATE">Duplicate of another issue</option>
                  </select>
                  <label className="muted" style={{ display: "block", marginBottom: 6 }}>Reason (recorded in the audit log)</label>
                  <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={3} style={{ width: "100%" }}
                    placeholder="What did you verify, and what fixed the underlying mismatch?" />
                  <div className="row" style={{ marginTop: 12 }}>
                    <button disabled={!outcome || !note.trim()} onClick={() => setConfirm(true)}>Resolve issue</button>
                  </div>
                </div>
              )}
              {issue.status === "OPEN" && !canManage && (
                <p className="notice">This role can inspect the issue and its evidence. A tenant administrator must assign or resolve it.</p>
              )}
            </div>
          </section>

          <section className="panel expected-actual-panel" style={{ marginTop: 18 }}>
            <div className="panelHeader"><div><h2>Expected vs actual</h2></div></div>
            <div className="split">
              <div><h3>Expected</h3><pre className="mono" style={{ whiteSpace: "pre-wrap", margin: 0 }}>{issue.expectedState ?? "—"}</pre></div>
              <div><h3>Actual</h3><pre className="mono" style={{ whiteSpace: "pre-wrap", margin: 0 }}>{issue.actualState ?? "—"}</pre></div>
            </div>
          </section>

          <section className="panel evidence-panel" style={{ marginTop: 18 }}>
            <div className="panelHeader"><div><h2>Evidence</h2><p className="sub">The data the worker captured when it raised this issue.</p></div></div>
            <div className="panelBody">
              <pre className="mono" style={{ whiteSpace: "pre-wrap", margin: 0, fontSize: 12 }}>{pretty(issue.evidence)}</pre>
            </div>
          </section>

          {audit.length > 0 && (
            <section className="panel activity-panel" style={{ marginTop: 18 }}>
              <div className="panelHeader"><div><h2>Activity</h2><p className="sub">Every action on this issue, from the auditable record.</p></div></div>
              <div className="panelBody">
                {audit.map((a, i) => {
                  const isResolution = a.action === "RECONCILIATION_ISSUE_RESOLVED";
                  const { outcome: o, note: n } = isResolution ? resolutionFields(a.metadata) : {};
                  const owner = a.action === "RECONCILIATION_ISSUE_ASSIGNED" ? assignedTo(a.metadata) : null;
                  return (
                    <div key={i} className="entry" style={{ alignItems: "flex-start", flexDirection: "column", gap: 4 }}>
                      <span>
                        <b>{a.action.replace(/_/g, " ").toLowerCase()}</b>
                        {o && <> — <span className="mono">{o.replace(/_/g, " ").toLowerCase()}</span></>}
                        {owner && <> — <span className="mono">{owner}</span></>}
                      </span>
                      {n && <span className="muted">{n}</span>}
                      <span className="muted" style={{ fontSize: 12 }}>
                        {dateTime(a.at)}{a.actorId ? ` · ${shortId(a.actorId)}` : ""}
                      </span>
                    </div>
                  );
                })}
              </div>
            </section>
          )}
          {auditError && <p className="error" style={{ marginTop: 18 }}>{auditError}</p>}
          {issue.status === "OPEN" && canManage && <button className="mobile-primary-action" onClick={() => {
            document.querySelector<HTMLElement>(".resolution-form")?.scrollIntoView({ behavior: "smooth", block: "center" });
          }}>Review resolution</button>}
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
