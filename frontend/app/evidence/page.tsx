"use client";

import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { EmptyState, SkeletonRows, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import { bytes, shortId } from "../lib/format";
import type { EvidenceExportView } from "../lib/types";

export default function EvidencePage() {
  const [items, setItems] = useState<EvidenceExportView[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.listEvidence().then(setItems).catch((e) => setError((e as Error).message));
  }, []);

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

      <section className="operations-strip compliance-strip" aria-label="Evidence export summary">
        <article><small>Exports generated</small><strong>{items?.length ?? "—"}</strong><span>Tenant-scoped packs</span></article>
        <article><small>Integrity method</small><strong>SHA-256</strong><span>Checksum stored at generation</span></article>
        <article><small>Source workflow</small><strong>Fraud cases</strong><span>Exports remain attributable and audited</span></article>
      </section>

      <section className="panel evidence-register">
        <div className="panelHeader">
          <div><h2>Export register</h2><p className="sub">Downloadable point-in-time records with their original integrity checksum.</p></div>
        </div>
        <table className="desktop-table">
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
                <td>
                  <button className="secondary" onClick={() => api.downloadEvidence(e.id)}>
                    Download
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="mobile-record-list">
          {items?.map((e) => (
            <article className="mobile-record evidence-record" key={e.id}>
              <div className="record-head"><div><small>Export</small><b className="mono">{shortId(e.id)}</b></div><StatusPill value={e.format} /></div>
              <div className="record-stats">
                <span><small>Resource</small><b>{e.resourceType.replace(/_/g, " ").toLowerCase()}</b></span>
                <span><small>Resource ID</small><b className="mono">{shortId(e.resourceId)}</b></span>
                <span><small>Size</small><b>{bytes(e.byteSize)}</b></span>
              </div>
              <div className="checksum-line"><small>SHA-256</small><span className="mono" title={e.checksum}>{e.checksum}</span></div>
              <button className="secondary" onClick={() => api.downloadEvidence(e.id)}>Download evidence</button>
            </article>
          ))}
        </div>
        {items !== null && items.length === 0 && (
          <EmptyState
            title="No evidence exported yet"
            hint="Export a pack from a fraud case (Cases → Export evidence). Exports are audited and may carry legal hold."
          />
        )}
      </section>
    </Shell>
  );
}
