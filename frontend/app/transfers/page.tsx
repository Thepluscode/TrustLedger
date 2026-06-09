"use client";

import { useEffect, useState, type FormEvent } from "react";
import Shell from "../components/Shell";
import { api } from "../lib/api";
import type { AccountView, AssessResponse, ExternalPaymentResponse, TransferResponse } from "../lib/types";

export default function TransfersPage() {
  const [accounts, setAccounts] = useState<AccountView[]>([]);
  const [source, setSource] = useState("");
  const [destination, setDestination] = useState("");
  const [amount, setAmount] = useState("100.00");
  const [result, setResult] = useState<TransferResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [extAmount, setExtAmount] = useState("200.00");
  const [extScenario, setExtScenario] = useState("success");
  const [extResult, setExtResult] = useState<ExternalPaymentResponse | null>(null);
  const [extError, setExtError] = useState<string | null>(null);

  const [assessResult, setAssessResult] = useState<AssessResponse | null>(null);

  async function runAssess() {
    setAssessResult(null);
    try {
      setAssessResult(await api.assessRisk("web-device", destination, amount));
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function submitExternal(e: FormEvent) {
    e.preventDefault();
    setExtError(null);
    setExtResult(null);
    try {
      const res = await api.createExternalTransfer(crypto.randomUUID(), {
        sourceAccountId: source,
        beneficiaryId: crypto.randomUUID(),
        amount: extAmount,
        currency: "GBP",
        reference: "ui external",
        deviceId: "web",
        currentCountry: "GB",
        scenario: extScenario,
      });
      setExtResult(res);
    } catch (err) {
      setExtError((err as Error).message);
    }
  }

  useEffect(() => {
    api.listAccounts().then((a) => {
      setAccounts(a);
      if (a[0]) setSource(a[0].id);
      if (a[1]) setDestination(a[1].id);
    }).catch((e) => setError((e as Error).message));
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setResult(null);
    try {
      const res = await api.createTransfer(crypto.randomUUID(), {
        sourceAccountId: source,
        destinationAccountId: destination,
        beneficiaryId: crypto.randomUUID(),
        amount,
        currency: "GBP",
        reference: "ui transfer",
        deviceId: "web",
        currentCountry: "GB",
      });
      setResult(res);
    } catch (err) {
      setError((err as Error).message);
    }
  }

  return (
    <Shell active="/transfers">
      <header className="topbar">
        <div>
          <p className="eyebrow">Transfers</p>
          <h1>New transfer</h1>
        </div>
      </header>

      <section className="panel">
        <form className="form" onSubmit={submit} style={{ padding: 16 }}>
          <label>Source account</label>
          <select value={source} onChange={(e) => setSource(e.target.value)}>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.id.slice(0, 8)}… ({a.availableBalance} {a.currency})</option>)}
          </select>
          <label>Destination account</label>
          <select value={destination} onChange={(e) => setDestination(e.target.value)}>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.id.slice(0, 8)}… ({a.availableBalance} {a.currency})</option>)}
          </select>
          <label>Amount</label>
          <input value={amount} onChange={(e) => setAmount(e.target.value)} />
          <div style={{ marginTop: 16 }} className="row">
            <button type="submit">Send transfer</button>
            <button type="button" className="secondary" onClick={runAssess}>Explain risk</button>
          </div>
        </form>
        {assessResult && (
          <div style={{ padding: "0 16px 16px" }}>
            <p>
              Risk <strong>{assessResult.riskScore}</strong> · decision{" "}
              <span className="badge">{assessResult.decision}</span>
            </p>
            <p className="muted">Signals: {assessResult.signals.length ? assessResult.signals.join(", ") : "none"}</p>
          </div>
        )}
        {error && <p className="error" style={{ padding: "0 16px 16px" }}>{error}</p>}
        {result && (
          <div style={{ padding: "0 16px 16px" }}>
            <p>
              Status <span className="badge">{result.status}</span>{" "}
              · risk <strong>{result.riskScore}</strong> · decision {result.decision}
            </p>
            <p className="muted">{result.message}</p>
          </div>
        )}
      </section>

      <section className="panel" style={{ marginTop: 18 }}>
        <div className="panelHeader">
          <div>
            <h2>External payment (sandbox rail)</h2>
            <p className="muted">Funds reserve on submit; a webhook or reconciliation settles or releases.</p>
          </div>
        </div>
        <form className="form" onSubmit={submitExternal} style={{ padding: 16 }}>
          <label>Source account</label>
          <select value={source} onChange={(e) => setSource(e.target.value)}>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.id.slice(0, 8)}… ({a.availableBalance} {a.currency})</option>)}
          </select>
          <label>Amount</label>
          <input value={extAmount} onChange={(e) => setExtAmount(e.target.value)} />
          <label>Provider scenario</label>
          <select value={extScenario} onChange={(e) => setExtScenario(e.target.value)}>
            <option value="success">success (settles via webhook)</option>
            <option value="slow">slow (pending settlement)</option>
            <option value="fail">fail (released immediately)</option>
            <option value="timeout">timeout (PENDING_UNKNOWN → reconciliation)</option>
          </select>
          <div style={{ marginTop: 16 }}>
            <button type="submit">Submit external payment</button>
          </div>
        </form>
        {extError && <p className="error" style={{ padding: "0 16px 16px" }}>{extError}</p>}
        {extResult && (
          <div style={{ padding: "0 16px 16px" }}>
            <p>
              Status <span className="badge">{extResult.status}</span>{" "}
              · ref <span className="muted">{extResult.providerReference ?? "—"}</span>
            </p>
            <p className="muted">{extResult.message}</p>
          </div>
        )}
      </section>
    </Shell>
  );
}
