"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import Shell from "../components/Shell";
import { ConfirmModal, EmptyState, SkeletonRows, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import { shortId } from "../lib/format";
import type { ApprovalView } from "../lib/types";

type Pending = { kind: "approve" | "reject"; item: ApprovalView } | null;

export default function ApprovalsPage() {
  const [items, setItems] = useState<ApprovalView[] | null>(null);
  const [pending, setPending] = useState<Pending>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [actionType, setActionType] = useState("TRANSFER_RELEASE");
  const [resourceType, setResourceType] = useState("TRANSFER");
  const [resourceId, setResourceId] = useState("");
  const [reason, setReason] = useState("");

  const load = useCallback(() => {
    api
      .listApprovals()
      .then(setItems)
      .catch((e) => {
        setItems([]);
        setError((e as Error).message);
      });
  }, []);

  useEffect(load, [load]);

  async function runPending(): Promise<void> {
    if (!pending) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const r =
        pending.kind === "approve"
          ? await api.approveApproval(pending.item.id)
          : await api.rejectApproval(pending.item.id);
      setNotice(`Request ${shortId(r.id)} ${r.status.toLowerCase()}.`);
      setPending(null);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function createRequest(event: FormEvent): Promise<void> {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const r = await api.createApproval(actionType, resourceType, resourceId.trim(), reason.trim());
      setNotice(`Approval requested (${shortId(r.id)}) — it now needs a second authorised approver.`);
      setResourceId("");
      setReason("");
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Shell active="/approvals">
      <header className="topbar">
        <div>
          <p className="eyebrow">Governance</p>
          <h1>Dual-control approvals</h1>
          <p className="sub">
            Pending requests you are authorised to decide. The queue is already filtered to your organisation-unit
            scope — a request outside your subtree is not listed and cannot be approved.
          </p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {notice && <p className="notice">{notice}</p>}

      <section className="panel">
        <table>
          <thead>
            <tr>
              <th>Request</th>
              <th>Action</th>
              <th>Resource</th>
              <th>Requested by</th>
              <th>Status</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {items === null && <SkeletonRows cols={6} />}
            {items?.map((a) => (
              <tr key={a.id}>
                <td className="mono">{shortId(a.id)}</td>
                <td>{a.actionType.replace(/_/g, " ").toLowerCase()}</td>
                <td>
                  <span className="muted">{a.resourceType.replace(/_/g, " ").toLowerCase()}</span>{" "}
                  <span className="mono">{shortId(a.resourceId)}</span>
                </td>
                <td className="mono muted">{shortId(a.requestedBy)}</td>
                <td><StatusPill value={a.status} /></td>
                <td className="row-actions">
                  <button disabled={busy} onClick={() => setPending({ kind: "approve", item: a })}>
                    Approve
                  </button>
                  <button className="danger" disabled={busy} onClick={() => setPending({ kind: "reject", item: a })}>
                    Reject
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {items !== null && items.length === 0 && (
          <EmptyState
            title="Nothing awaiting your approval"
            hint="Requests appear here when someone raises a dual-control action on a resource inside your scope. An empty queue may also mean you lack approval authority."
          />
        )}
      </section>

      <section className="panel" style={{ marginTop: 18 }}>
        <h2>Raise a request</h2>
        <p className="sub">
          Records that a sensitive action was asked for, by whom, and why. You cannot approve your own request — that
          refusal comes from the backend, not from this page hiding a button.
        </p>
        <form className="form-grid" onSubmit={createRequest}>
          <label>
            Action type
            <input value={actionType} onChange={(e) => setActionType(e.target.value.toUpperCase())} required />
          </label>
          <label>
            Resource type
            <input value={resourceType} onChange={(e) => setResourceType(e.target.value.toUpperCase())} required />
          </label>
          <label>
            Resource id
            <input
              value={resourceId}
              onChange={(e) => setResourceId(e.target.value)}
              placeholder="uuid of the transfer, payout or config"
              required
            />
          </label>
          <label>
            Reason
            <input
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="why this action is needed — this is what the approver reads"
              required
            />
          </label>
          <button type="submit" disabled={busy}>
            {busy ? "Submitting…" : "Request approval"}
          </button>
        </form>
      </section>

      {pending && (
        <ConfirmModal
          open
          title={pending.kind === "approve" ? "Approve request" : "Reject request"}
          body={
            pending.kind === "approve"
              ? "Approving records your identity against this action and allows it to proceed. The decision is audited and cannot be edited afterwards."
              : "Rejecting stops the action. The decision is audited and cannot be edited afterwards."
          }
          confirmWord={pending.kind === "approve" ? "APPROVE" : "REJECT"}
          confirmLabel={pending.kind === "approve" ? "Approve" : "Reject"}
          danger={pending.kind === "reject"}
          busy={busy}
          onConfirm={runPending}
          onCancel={() => setPending(null)}
        />
      )}
    </Shell>
  );
}
