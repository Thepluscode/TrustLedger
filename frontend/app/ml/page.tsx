"use client";

import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { EmptyState, SkeletonRows, StatusPill } from "../components/ui";
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

export default function MlPage() {
  const [models, setModels] = useState<
    { modelName: string; version: string; status: string; deploymentMode: string }[] | null
  >(null);
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
          <p className="eyebrow">Fraud Intelligence</p>
          <h1>ML monitoring</h1>
          <p className="sub">Model registry, governance state, and per-transaction explainability.</p>
        </div>
      </header>
      {/* §12.3 banner — non-negotiable honesty about shadow mode */}
      <div className="notice">
        <b>ML is running in shadow mode.</b> It cannot directly move or block money — scores inform analysts; the
        rules engine and human decisions gate every transfer.
      </div>
      {error && <p className="error">{error}</p>}

      <section className="panel">
        <div className="panelHeader">
          <h2>Registered models</h2>
        </div>
        <table>
          <thead>
            <tr>
              <th>Model</th>
              <th>Version</th>
              <th>Status</th>
              <th>Deployment mode</th>
            </tr>
          </thead>
          <tbody>
            {models === null && <SkeletonRows cols={4} rows={2} />}
            {models?.map((m, i) => (
              <tr key={i}>
                <td>{m.modelName}</td>
                <td className="mono">{m.version}</td>
                <td><StatusPill value={m.status} /></td>
                <td className="muted">{m.deploymentMode.replace(/_/g, " ").toLowerCase()}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {models !== null && models.length === 0 && (
          <EmptyState
            title="No models registered"
            hint="Scoring still runs in shadow with the baseline. Register a model via the ML API to govern promotion and rollback here."
          />
        )}
      </section>

      <section className="panel" style={{ marginTop: 18 }}>
        <div className="panelHeader">
          <div>
            <h2>Explain a transaction score</h2>
            <p className="sub">Rules score vs ML score, top contributing factors, and latency for any scored transaction.</p>
          </div>
        </div>
        <div className="panelBody">
          <div className="row">
            <input
              value={txId}
              onChange={(e) => setTxId(e.target.value)}
              placeholder="transaction id"
              style={{ minWidth: 320, flex: 1 }}
              aria-label="Transaction id"
            />
            <button onClick={lookup} disabled={!txId.trim()}>
              Look up
            </button>
          </div>
          {scores?.length === 0 && (
            <EmptyState
              title="No ML score for that transaction"
              hint="Shadow scores are written when a transfer is fraud-scored — check the transaction id from the transfer result or audit log."
            />
          )}
          {scores?.map((s, i) => {
            const pct = parseFloat(s.fraudProbability) * 100;
            return (
              <div key={i} style={{ marginTop: 16 }}>
                <p className="row" style={{ gap: 10, alignItems: "center" }}>
                  <span className={`risk ${s.riskBand.toLowerCase()}`}>
                    {s.riskBand} · {pct.toFixed(1)}%
                  </span>
                  <StatusPill value={s.shadowMode ? "SHADOW" : "ACTIVE"} />
                  <span className="muted mono">
                    {s.modelVersion} · features {s.featureSetVersion} · {s.latencyMs}ms
                  </span>
                </p>
                <p className="muted" style={{ marginBottom: 4 }}>Top contributing factors:</p>
                <ol style={{ margin: "4px 0 0", paddingLeft: 20 }}>
                  {factors(s.explanationJson).map((f, j) => (
                    <li key={j}>
                      {f.label} <span className="muted">(+{f.contribution.toFixed(2)})</span>
                    </li>
                  ))}
                </ol>
              </div>
            );
          })}
        </div>
      </section>
    </Shell>
  );
}
