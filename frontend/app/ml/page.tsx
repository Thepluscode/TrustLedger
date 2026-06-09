"use client";

import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { api } from "../lib/api";

type Factor = { feature: string; label: string; contribution: number };
type MlScore = {
  modelVersion: string;
  featureSetVersion: string;
  fraudProbability: string;
  riskBand: string;
  explanationJson: string;
  shadowMode: boolean;
  latencyMs: number;
};

const BAND_COLOR: Record<string, string> = {
  LOW: "var(--ok, #34d399)",
  MEDIUM: "#fbbf24",
  HIGH: "#fb923c",
  CRITICAL: "var(--error, #f87171)",
};

export default function MlPage() {
  const [models, setModels] = useState<{ modelName: string; version: string; status: string; deploymentMode: string }[]>([]);
  const [txId, setTxId] = useState("");
  const [scores, setScores] = useState<MlScore[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.listMlModels().then(setModels).catch((e) => setError((e as Error).message));
  }, []);

  async function lookup() {
    setError(null);
    setScores(null);
    try {
      setScores(await api.getMlScores(txId.trim()));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function factors(json: string): Factor[] {
    try {
      return JSON.parse(json) as Factor[];
    } catch {
      return [];
    }
  }

  return (
    <Shell active="/ml">
      <header className="topbar">
        <div>
          <p className="eyebrow">ML Fraud Intelligence</p>
          <h1>Model scores & governance</h1>
        </div>
      </header>
      {error && <p className="error">{error}</p>}

      <section className="panel" style={{ padding: 16 }}>
        <h2>Registered models</h2>
        <p className="muted">ML runs in shadow / analyst-assist only — it never moves money.</p>
        <table>
          <thead><tr><th>Model</th><th>Version</th><th>Status</th><th>Deployment mode</th></tr></thead>
          <tbody>
            {models.map((m, i) => (
              <tr key={i}><td>{m.modelName}</td><td>{m.version}</td><td><span className="badge">{m.status}</span></td><td>{m.deploymentMode}</td></tr>
            ))}
            {models.length === 0 && <tr><td colSpan={4} className="muted">No models registered (scoring still runs in shadow with the baseline).</td></tr>}
          </tbody>
        </table>
      </section>

      <section className="panel" style={{ padding: 16 }}>
        <h2>Explain a transaction score</h2>
        <div className="row">
          <input value={txId} onChange={(e) => setTxId(e.target.value)} placeholder="transaction id" style={{ minWidth: 320 }} />
          <button onClick={lookup} disabled={!txId.trim()}>Look up</button>
        </div>
        {scores?.length === 0 && <p className="muted">No ML score for that transaction.</p>}
        {scores?.map((s, i) => (
          <div key={i} style={{ marginTop: 16 }}>
            <p>
              Probability <strong>{(parseFloat(s.fraudProbability) * 100).toFixed(1)}%</strong> ·{" "}
              <span className="badge" style={{ color: BAND_COLOR[s.riskBand] }}>{s.riskBand}</span> ·{" "}
              {s.shadowMode ? "shadow" : "active"} · {s.modelVersion} · {s.latencyMs}ms
            </p>
            <p className="muted">Top contributing factors:</p>
            <ol>
              {factors(s.explanationJson).map((f, j) => (
                <li key={j}>{f.label} <span className="muted">(+{f.contribution.toFixed(2)})</span></li>
              ))}
            </ol>
          </div>
        ))}
      </section>
    </Shell>
  );
}
