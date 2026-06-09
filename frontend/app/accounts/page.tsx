"use client";

import { useEffect, useState, type FormEvent } from "react";
import Shell from "../components/Shell";
import { api } from "../lib/api";
import type { AccountView } from "../lib/types";

export default function AccountsPage() {
  const [accounts, setAccounts] = useState<AccountView[]>([]);
  const [currency, setCurrency] = useState("GBP");
  const [opening, setOpening] = useState("1000.00");
  const [error, setError] = useState<string | null>(null);

  function load() {
    api.listAccounts().then(setAccounts).catch((e) => setError((e as Error).message));
  }
  useEffect(load, []);

  async function create(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      await api.createAccount(currency, opening);
      load();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  return (
    <Shell active="/accounts">
      <header className="topbar">
        <div>
          <p className="eyebrow">Accounts</p>
          <h1>Accounts</h1>
        </div>
      </header>

      <section className="panel">
        <div className="panelHeader">
          <h2>Open an account</h2>
        </div>
        <form className="row" onSubmit={create} style={{ padding: "0 16px 16px" }}>
          <div>
            <label>Currency</label>
            <input value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
          </div>
          <div>
            <label>Opening balance</label>
            <input value={opening} onChange={(e) => setOpening(e.target.value)} />
          </div>
          <button type="submit">Create</button>
        </form>
        {error && <p className="error" style={{ padding: "0 16px" }}>{error}</p>}
      </section>

      <section className="panel" style={{ marginTop: 18 }}>
        <table>
          <thead>
            <tr><th>Account</th><th>Currency</th><th>Available</th><th>Pending</th><th>Status</th></tr>
          </thead>
          <tbody>
            {accounts.map((a) => (
              <tr key={a.id}>
                <td>{a.id.slice(0, 8)}…</td>
                <td>{a.currency}</td>
                <td>{a.availableBalance}</td>
                <td>{a.pendingBalance}</td>
                <td><span className="badge">{a.status}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </Shell>
  );
}
