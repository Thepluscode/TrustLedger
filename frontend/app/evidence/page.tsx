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

      <section className="panel">
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
                <td>
                  <button className="secondary" onClick={() => api.downloadEvidence(e.id)}>
                    Download
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
    </Shell>
  );
}
