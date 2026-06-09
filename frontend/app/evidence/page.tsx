"use client";

import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { api } from "../lib/api";
import type { EvidenceExportView } from "../lib/types";

export default function EvidencePage() {
  const [items, setItems] = useState<EvidenceExportView[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.listEvidence().then(setItems).catch((e) => setError((e as Error).message));
  }, []);

  return (
    <Shell active="/evidence">
      <header className="topbar">
        <div>
          <p className="eyebrow">Evidence & Compliance</p>
          <h1>Evidence exports</h1>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      <section className="panel">
        <table>
          <thead>
            <tr><th>Export</th><th>Resource</th><th>Format</th><th>Size</th><th>Checksum</th><th></th></tr>
          </thead>
          <tbody>
            {items.map((e) => (
              <tr key={e.id}>
                <td>{e.id.slice(0, 8)}…</td>
                <td>{e.resourceType}</td>
                <td><span className="badge">{e.format}</span></td>
                <td>{e.byteSize} B</td>
                <td className="muted" style={{ fontFamily: "monospace", fontSize: 12 }}>{e.checksum.slice(0, 23)}…</td>
                <td><button className="secondary" onClick={() => api.downloadEvidence(e.id)}>Download</button></td>
              </tr>
            ))}
            {items.length === 0 && <tr><td colSpan={6} className="muted">No evidence exported yet. Export one from a fraud case.</td></tr>}
          </tbody>
        </table>
      </section>
    </Shell>
  );
}
