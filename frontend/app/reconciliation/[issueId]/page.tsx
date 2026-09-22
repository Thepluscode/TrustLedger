"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ConfirmModal, SeverityPill, StatusPill } from "../../components/ui";
import Shell from "../../components/Shell";
import { api, getSession } from "../../lib/api";
import { dateTime, money, shortId } from "../../lib/format";
import { RESOLUTION_REASONS, WORKING_TRANSITIONS, isClosed, recordsBySide, resolutionBlocker } from "../../lib/recon";
import type { ReconIssueActivity, ReconciliationAuditEntry, ReconciliationIssue, TeamMember } from "../../lib/types";

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
  const [activity, setActivity] = useState<ReconIssueActivity[]>([]);
  const [evidenceRef, setEvidenceRef] = useState("");
  const [comment, setComment] = useState("");
  const [evidenceFile, setEvidenceFile] = useState<File | null>(null);
  const [evidenceNote, setEvidenceNote] = useState("");
  const role = getSession()?.role.toUpperCase() ?? "";
  const canManage = role === "OWNER" || role === "ADMIN" || role === "TENANT_ADMIN" || role === "RECON_OPERATOR";

  async function loadAudit() {
    if (!id) return;
    try {
      const [auditRows, history] = await Promise.all([api.reconciliationIssueAudit(id), api.reconciliationIssueActivity(id)]);
      setAudit(auditRows);
      setActivity(history);
      setAuditError(null);
    } catch (e) {
      setAuditError(`Activity unavailable: ${(e as Error).message}`);
    }
  }

  /** Every write sends the version this tab loaded. If someone else got there first the server refuses, and we reload. */
  async function change(fn: (version: number) => Promise<ReconciliationIssue | void>) {
    if (!id || !issue) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await fn(issue.version);
      setIssue(updated || (await api.getReconciliationIssue(id)));
      await loadAudit();
    } catch (e) {
      setError((e as Error).message);
      api.getReconciliationIssue(id).then(setIssue).catch(() => undefined);
    } finally {
      setBusy(false);
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
      // The assignee list is gated on RECON_ISSUE_WORK, not on user administration, so an operator can use it.
      api.reconciliationAssignees()
        .then((list) => setMembers(list.map((u) => ({ ...u, createdAt: "" }))))
        .catch((e) => setError(`Unable to load assignable owners: ${(e as Error).message}`));
    }
  }, [canManage, id]);

  async function downloadEvidence(seq: number, filename: string) {
    if (!id) return;
    try {
      const blob = await api.downloadReconciliationIssueEvidence(id, seq);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const assign = () => change((v) => api.assignReconciliationIssue(id, ownerChoice || null, v));

  async function resolve() {
    await change((v) => api.resolveReconciliationIssue(id, outcome, note, evidenceRef, v));
    setConfirm(false);
  }

  const attached = activity.filter((a) => a.kind === "EVIDENCE_ADDED" && a.evidenceStorageKey);
  const blocker = resolutionBlocker(outcome, note, evidenceRef);
  const reason = RESOLUTION_REASONS.find((r) => r.code === outcome);

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
                <SeverityPill value={issue.severity} /> <StatusPill value={issue.lifecycleState} />
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
                {issue.ruleId && (
                  <div className="entry"><span className="muted">Raised by rule</span>
                    <span><span className="mono">{issue.ruleId}</span> · <span className="mono">{issue.ruleVersion}</span></span></div>
                )}
                {issue.caseId && (
                  <div className="entry"><span className="muted">Case</span><Link href={`/reconciliation/cases/${issue.caseId}`}>open case</Link></div>
                )}
                {issue.reasonCode && (
                  <div className="entry"><span className="muted">Decision</span>
                    <span><span className="mono">{issue.reasonCode.replace(/_/g, " ").toLowerCase()}</span>{issue.resolutionNote ? ` — ${issue.resolutionNote}` : ""}</span></div>
                )}
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
              {!isClosed(issue.lifecycleState) && canManage && (WORKING_TRANSITIONS[issue.lifecycleState] ?? []).length > 0 && (
                <div className="resolution-form">
                  <span className="muted" style={{ display: "block", marginBottom: 6 }}>Working state</span>
                  <div className="row" style={{ gap: 8 }}>
                    {(WORKING_TRANSITIONS[issue.lifecycleState] ?? []).map((to) => (
                      <button key={to} className="secondary" disabled={busy || !issue.ownerUserId}
                        title={issue.ownerUserId ? undefined : "Assign an owner first"}
                        onClick={() => change((v) => api.transitionReconciliationIssue(id, to, v))}>
                        Move to {to.replace(/_/g, " ").toLowerCase()}</button>
                    ))}
                  </div>
                </div>
              )}
              {issue.status === "OPEN" && canManage && (
                <div className="resolution-form">
                  <label className="muted" htmlFor="issue-reason" style={{ display: "block", marginBottom: 6 }}>Decision</label>
                  <select id="issue-reason" value={outcome} onChange={(e) => setOutcome(e.target.value)} style={{ width: "100%", marginBottom: 12 }}>
                    <option value="">Select a reason…</option>
                    {RESOLUTION_REASONS.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
                  </select>
                  {reason?.evidenceRequired && (
                    <>
                      <label className="muted" htmlFor="issue-evidence-ref" style={{ display: "block", marginBottom: 6 }}>Supporting evidence (attach a file below first)</label>
                      <select id="issue-evidence-ref" value={evidenceRef} onChange={(e) => setEvidenceRef(e.target.value)} style={{ width: "100%", marginBottom: 12 }}>
                        <option value="">{attached.length === 0 ? "No evidence attached yet" : "Select the evidence this decision relies on…"}</option>
                        {attached.map((a) => <option key={a.seq} value={a.evidenceStorageKey ?? ""}>{a.evidenceFilename} · {a.evidenceSha256?.slice(0, 12)}…</option>)}
                      </select>
                    </>
                  )}
                  <label className="muted" style={{ display: "block", marginBottom: 6 }}>Reason (recorded in the audit log)</label>
                  <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={3} style={{ width: "100%" }}
                    placeholder="What did you verify, and what fixed the underlying mismatch?" />
                  <div className="row" style={{ marginTop: 12 }}>
                    <button disabled={blocker !== null} title={blocker ?? undefined} onClick={() => setConfirm(true)}>
                      {reason?.closesAs === "DISMISSED" ? "Dismiss exception" : "Resolve exception"}</button>
                    {blocker && <span className="muted">{blocker}</span>}
                  </div>
                </div>
              )}
              {issue.status === "OPEN" && !canManage && (
                <p className="notice">This role can inspect the exception and its evidence. A reconciliation operator or tenant administrator must assign or resolve it.</p>
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

          {Object.keys(recordsBySide(issue.evidence)).length > 0 && (
            <section className="panel" style={{ marginTop: 18 }}>
              <div className="panelHeader"><div><h2>Source records</h2><p className="sub">The exact rows this exception was raised from, by side. Row numbers refer to the file as supplied.</p></div></div>
              <div className="split">
                {Object.entries(recordsBySide(issue.evidence)).map(([sideName, rows]) => (
                  <div key={sideName}>
                    <h3>{sideName.replace(/_/g, " ").toLowerCase()}</h3>
                    {rows.map((r) => (
                      <p key={r.recordId} style={{ margin: "0 0 8px" }}>
                        <span className="mono">{r.sourceSystem}</span> · row {r.rowNumber}<br />
                        <span className="muted mono" style={{ fontSize: 12 }} title={r.fileSha256}>file {r.fileSha256.slice(0, 16)}…</span>
                      </p>
                    ))}
                  </div>
                ))}
              </div>
            </section>
          )}

          {activity.length > 0 && (
            <section className="panel" style={{ marginTop: 18 }}>
              <div className="panelHeader"><div><h2>Working history</h2><p className="sub">Append-only. Comments, evidence and every state change, in order.</p></div></div>
              <div className="panelBody">
                {activity.map((a) => (
                  <div key={a.seq} className="entry" style={{ alignItems: "flex-start", flexDirection: "column", gap: 4 }}>
                    <span><b>{a.seq}. {a.kind.replace(/_/g, " ").toLowerCase()}</b>
                      {a.fromState && a.toState && a.fromState !== a.toState && <> — {a.fromState.toLowerCase().replace(/_/g, " ")} → {a.toState.toLowerCase().replace(/_/g, " ")}</>}
                      {a.evidenceFilename && <> — <button className="linklike" onClick={() => downloadEvidence(a.seq, a.evidenceFilename ?? "evidence")}>{a.evidenceFilename}</button> <span className="muted mono">{a.evidenceSha256?.slice(0, 12)}…</span></>}</span>
                    {a.body && (a.kind === "COMMENT" || a.kind === "EVIDENCE_ADDED" || a.kind === "RAISED") && <span className="muted">{a.body}</span>}
                    <span className="muted" style={{ fontSize: 12 }}>{dateTime(a.createdAt)}{a.actorId ? ` · ${shortId(a.actorId)}` : " · system"}</span>
                  </div>
                ))}
                {!isClosed(issue.lifecycleState) && canManage && (
                  <div className="resolution-form">
                    <label className="muted" htmlFor="issue-comment" style={{ display: "block", marginBottom: 6 }}>Add a comment</label>
                    <textarea id="issue-comment" value={comment} onChange={(e) => setComment(e.target.value)} rows={2} style={{ width: "100%" }} maxLength={4000} />
                    <div className="row" style={{ marginTop: 8 }}>
                      <button className="secondary" disabled={busy || !comment.trim()}
                        onClick={() => change(async () => { await api.commentOnReconciliationIssue(id, comment); setComment(""); })}>Add comment</button>
                    </div>
                    <label className="muted" htmlFor="issue-evidence-file" style={{ display: "block", margin: "14px 0 6px" }}>Attach evidence (max 10 MB)</label>
                    <div className="row" style={{ gap: 8, flexWrap: "wrap" }}>
                      <input id="issue-evidence-file" type="file" onChange={(e) => setEvidenceFile(e.target.files?.[0] ?? null)} />
                      <input value={evidenceNote} onChange={(e) => setEvidenceNote(e.target.value)} placeholder="What this file shows" style={{ flex: 1, minWidth: 180 }} />
                      <button className="secondary" disabled={busy || !evidenceFile}
                        onClick={() => change(async () => { await api.addReconciliationIssueEvidence(id, evidenceFile!, evidenceNote); setEvidenceFile(null); setEvidenceNote(""); })}>Attach</button>
                    </div>
                  </div>
                )}
              </div>
            </section>
          )}

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
        title="Close this exception"
        body="This decision is final and is recorded with your identity in the working history and the audit log. A closed exception cannot be reopened; a recurrence raises a new one."
        confirmWord="RESOLVE"
        confirmLabel="Resolve issue"
        busy={busy}
        onConfirm={resolve}
        onCancel={() => setConfirm(false)}
      />
    </Shell>
  );
}
