"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import Shell from "../components/Shell";
import { ConfirmModal, EmptyState, SkeletonRows, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import { bytes, shortId } from "../lib/format";
import type { EvidenceExportView } from "../lib/types";

/** Deletion modes the backend's retention service accepts. */
const DELETION_MODES = ["SOFT_DELETE", "HARD_DELETE"] as const;

export default function EvidencePage() {
  const [items, setItems] = useState<EvidenceExportView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<EvidenceExportView | null>(null);

  // Retention policy form. Defaults are conservative: archive on, soft delete, holds honoured.
  const [resourceType, setResourceType] = useState("FRAUD_CASE");
  const [retentionDays, setRetentionDays] = useState(2555); // ~7 years, a common financial floor
  const [archiveEnabled, setArchiveEnabled] = useState(true);
  const [deletionMode, setDeletionMode] = useState<string>(DELETION_MODES[0]);
  const [legalHoldEnabled, setLegalHoldEnabled] = useState(true);
  const [savingPolicy, setSavingPolicy] = useState(false);

  const load = useCallback(() => {
    api
      .listEvidence()
      .then(setItems)
      .catch((e) => setError((e as Error).message));
  }, []);

  useEffect(load, [load]);

  /** Wraps an action so a failure surfaces instead of leaving a button silently dead. */
  async function run(id: string, message: string, action: () => Promise<unknown>): Promise<void> {
    setBusyId(id);
    setError(null);
    setNotice(null);
    try {
      await action();
      setNotice(message);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusyId(null);
    }
  }

  async function savePolicy(event: FormEvent): Promise<void> {
    event.preventDefault();
    setSavingPolicy(true);
    setError(null);
    setNotice(null);
    try {
      await api.upsertRetentionPolicy({
        resourceType,
        retentionDays,
        archiveEnabled,
        deletionMode,
        legalHoldEnabled,
      });
      setNotice(`Retention policy saved for ${resourceType}.`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSavingPolicy(false);
    }
  }

  return (
    <Shell active="/evidence">
      <header className="topbar">
        <div>
          <p className="eyebrow">Compliance</p>
          <h1>Evidence exports</h1>
          <p className="sub">
            Every pack is checksummed at generation — the checksum proves the evidence hasn&apos;t changed since export.
          </p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {notice && <p className="notice">{notice}</p>}

      <section className="panel evidence-list">
        <table>
          <thead>
            <tr>
              <th>Export</th>
              <th>Resource</th>
              <th>Format</th>
              <th className="num">Size</th>
              <th>Checksum</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {items === null && <SkeletonRows cols={6} />}
            {items?.map((e) => (
              <tr key={e.id}>
                <td className="mono">{shortId(e.id)}</td>
                <td>
                  <span className="muted">{e.resourceType.replace(/_/g, " ").toLowerCase()}</span>{" "}
                  <span className="mono">{shortId(e.resourceId)}</span>
                </td>
                <td><StatusPill value={e.format} /></td>
                <td className="num">{bytes(e.byteSize)}</td>
                <td className="mono muted" title={e.checksum}>{e.checksum.slice(0, 23)}…</td>
                <td className="row-actions">
                  <button className="secondary" onClick={() => api.downloadEvidence(e.id)}>
                    Download
                  </button>
                  {/* Hold and release are separate buttons rather than one toggle: the list endpoint
                      does not return current hold state, and a toggle would have to guess at it. */}
                  <button
                    className="secondary"
                    disabled={busyId === e.id}
                    onClick={() =>
                      run(e.id, `Legal hold placed on ${shortId(e.id)}.`, () => api.setEvidenceLegalHold(e.id, true))
                    }
                  >
                    Hold
                  </button>
                  <button
                    className="secondary"
                    disabled={busyId === e.id}
                    onClick={() =>
                      run(e.id, `Legal hold released on ${shortId(e.id)}.`, () => api.setEvidenceLegalHold(e.id, false))
                    }
                  >
                    Release
                  </button>
                  <button className="danger" disabled={busyId === e.id} onClick={() => setPendingDelete(e)}>
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {items !== null && items.length === 0 && (
          <EmptyState
            title="No evidence exported yet"
            hint="Export a pack from a fraud case (Cases → Export evidence). Exports are audited and may carry legal hold."
          />
        )}
      </section>

      <section className="panel">
        <h2>Retention policy</h2>
        <p className="sub">
          Applies to every export of a resource type. A legal hold always wins over retention — an export under
          hold is refused deletion by the backend, not merely hidden here.
        </p>
        <form className="form-grid" onSubmit={savePolicy}>
          <label>
            Resource type
            <input value={resourceType} onChange={(ev) => setResourceType(ev.target.value.toUpperCase())} required />
          </label>
          <label>
            Retention (days)
            <input
              type="number"
              min={1}
              value={retentionDays}
              onChange={(ev) => setRetentionDays(Number(ev.target.value))}
              required
            />
          </label>
          <label>
            Deletion mode
            <select value={deletionMode} onChange={(ev) => setDeletionMode(ev.target.value)}>
              {DELETION_MODES.map((m) => (
                <option key={m} value={m}>
                  {m.replace(/_/g, " ").toLowerCase()}
                </option>
              ))}
            </select>
          </label>
          <label className="checkbox">
            <input type="checkbox" checked={archiveEnabled} onChange={(ev) => setArchiveEnabled(ev.target.checked)} />
            Archive before deleting
          </label>
          <label className="checkbox">
            <input
              type="checkbox"
              checked={legalHoldEnabled}
              onChange={(ev) => setLegalHoldEnabled(ev.target.checked)}
            />
            Honour legal holds
          </label>
          <button type="submit" disabled={savingPolicy}>
            {savingPolicy ? "Saving…" : "Save policy"}
          </button>
        </form>
      </section>

      <ConfirmModal
        open={pendingDelete !== null}
        title="Delete evidence export"
        body={
          <p>
            Deleting <span className="mono">{pendingDelete ? shortId(pendingDelete.id) : ""}</span> removes an audit
            artefact. If the export is under legal hold the backend will refuse — that refusal is the control
            working, not an error to route around.
          </p>
        }
        confirmWord="DELETE"
        confirmLabel="Delete export"
        danger
        busy={busyId === pendingDelete?.id}
        onCancel={() => setPendingDelete(null)}
        onConfirm={() => {
          const target = pendingDelete;
          setPendingDelete(null);
          if (target) {
            void run(target.id, `Export ${shortId(target.id)} deleted.`, () => api.deleteEvidenceExport(target.id));
          }
        }}
      />
    </Shell>
  );
}
