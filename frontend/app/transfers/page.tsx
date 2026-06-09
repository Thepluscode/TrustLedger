"use client";

import { useEffect, useState, type FormEvent } from "react";
import Shell from "../components/Shell";
import { api } from "../lib/api";
import type { AccountView, TransferResponse } from "../lib/types";

export default function TransfersPage() {
  const [accounts, setAccounts] = useState<AccountView[]>([]);
  const [source, setSource] = useState("");
  const [destination, setDestination] = useState("");
  const [amount, setAmount] = useState("100.00");
  const [result, setResult] = useState<TransferResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

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
          <div style={{ marginTop: 16 }}>
            <button type="submit">Send transfer</button>
          </div>
        </form>
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
    </Shell>
  );
}
