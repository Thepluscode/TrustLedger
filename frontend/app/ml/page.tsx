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

  const registered = models ?? [];
  const shadowModels = registered.filter((model) => model.deploymentMode.toUpperCase().includes("SHADOW")).length;

  return (
    <Shell active="/ml">
      <header className="topbar">
        <div>
          <p className="eyebrow">Fraud Intelligence</p>
          <h1>ML monitoring</h1>
          <p className="sub">Model registry, governance state, and per-transaction explainability.</p>
        </div>
      </header>
      <div className="safety-boundary ml-safety-boundary">
        <strong>Shadow-mode boundary</strong>
        <span>ML scores are recorded for evaluation. They cannot move, hold, or reject money; rules and authorised analysts govern outcomes.</span>
      </div>
      {error && <p className="error">{error}</p>}

      <section className="operations-strip" aria-label="Model registry summary">
        <div><span>Registered models</span><strong>{models ? registered.length : "—"}</strong></div>
        <div><span>Shadow deployments</span><strong>{models ? shadowModels : "—"}</strong></div>
        <div><span>Decision authority</span><strong>Rules + review</strong></div>
      </section>

      <div className="ml-workbench">
        <section className="panel model-register">
          <div className="panelHeader">
            <div><h2>Model register</h2><p className="sub">Governed versions and their current deployment posture.</p></div>
          </div>
          <table className="desktop-table">
            <thead><tr><th>Model</th><th>Version</th><th>Status</th><th>Mode</th></tr></thead>
            <tbody>
              {models === null && <SkeletonRows cols={4} rows={2} />}
              {models?.map((model, index) => (
                <tr key={`${model.modelName}-${model.version}-${index}`}><td>{model.modelName}</td><td className="mono">{model.version}</td><td><StatusPill value={model.status} /></td><td className="muted">{model.deploymentMode.replace(/_/g, " ").toLowerCase()}</td></tr>
              ))}
            </tbody>
          </table>
          <div className="mobile-record-list">
            {models?.map((model, index) => (
              <article className="mobile-record model-record" key={`${model.modelName}-${model.version}-${index}`}>
                <div className="record-head"><div><small>Model</small><strong>{model.modelName}</strong></div><StatusPill value={model.status} /></div>
                <div className="record-stats"><span><small>Version</small><b className="mono">{model.version}</b></span><span><small>Mode</small><b>{model.deploymentMode.replace(/_/g, " ").toLowerCase()}</b></span></div>
              </article>
            ))}
          </div>
          {models !== null && models.length === 0 && <EmptyState title="No models registered" hint="The registry is empty. Model outputs remain unavailable until a governed version is registered." />}
        </section>

        <section className="panel score-inspector">
          <div className="panelHeader"><div><h2>Transaction explanation</h2><p className="sub">Retrieve the recorded shadow score and its contributing factors.</p></div></div>
          <div className="panelBody">
            <div className="lookup-control">
              <input
                value={txId}
                onChange={(e) => setTxId(e.target.value)}
                placeholder="Transaction ID"
                aria-label="Transaction id"
              />
              <button onClick={lookup} disabled={!txId.trim()}>Retrieve</button>
            </div>
            {scores === null && <p className="inspector-prompt">Enter a transfer ID to inspect the evidence stored with its shadow score.</p>}
            {scores?.length === 0 && <EmptyState title="No score recorded" hint="Check the transfer ID. Shadow scores are written when the fraud gate evaluates a transfer." />}
            {scores?.map((score, index) => {
              const probability = parseFloat(score.fraudProbability) * 100;
              return (
                <div className="score-readout" key={`${score.modelVersion}-${index}`}>
                  <div className="score-readout-head">
                    <div><small>Fraud probability</small><strong>{probability.toFixed(1)}%</strong></div>
                    <div><small>Risk band</small><span className={`risk ${score.riskBand.toLowerCase()}`}>{score.riskBand}</span></div>
                    <div><small>Mode</small><StatusPill value={score.shadowMode ? "SHADOW" : "ACTIVE"} /></div>
                  </div>
                  <dl className="model-facts"><div><dt>Model</dt><dd className="mono">{score.modelVersion}</dd></div><div><dt>Feature set</dt><dd className="mono">{score.featureSetVersion}</dd></div><div><dt>Latency</dt><dd>{score.latencyMs}ms</dd></div></dl>
                  <div className="factor-list">
                    <h3>Recorded factors</h3>
                    {factors(score.explanationJson).map((factor, factorIndex) => (
                      <div key={`${factor.feature}-${factorIndex}`}><span>{factor.label}</span><b>+{factor.contribution.toFixed(2)}</b></div>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        </section>
      </div>
    </Shell>
  );
}
