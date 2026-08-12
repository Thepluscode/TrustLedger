"use client";

import Link from "next/link";
import { useEffect, useState, type FormEvent } from "react";
import Shell from "../components/Shell";
import { EmptyState, SkeletonRows, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import { money, shortId } from "../lib/format";
import type { AccountView } from "../lib/types";

export default function AccountsPage() {
  const [accounts, setAccounts] = useState<AccountView[] | null>(null);
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

  const summaryCurrency = accounts?.find((a) => a.currency === "GBP")?.currency ?? accounts?.[0]?.currency ?? "GBP";
  const summaryAccounts = (accounts ?? []).filter((a) => a.currency === summaryCurrency);
  const total = (field: "availableBalance" | "pendingBalance" | "postedBalance") =>
    summaryAccounts.reduce((sum, account) => sum + Number(account[field]), 0);

  return (
    <Shell active="/accounts">
      <header className="topbar">
        <div>
          <p className="eyebrow">Money</p>
          <h1>Accounts</h1>
          <p className="sub">Available is spendable now; pending is reserved by holds or in-flight external payments.</p>
        </div>
      </header>

      <section className="grid account-metrics" aria-label={`${summaryCurrency} account summary`}>
        <article className="card"><span>Total posted</span><strong>{money(total("postedBalance"), summaryCurrency)}</strong><small>{summaryAccounts.length} {summaryCurrency} accounts</small></article>
        <article className="card"><span>Available</span><strong>{money(total("availableBalance"), summaryCurrency)}</strong><small>Spendable now</small></article>
        <article className="card"><span>Reserved / pending</span><strong>{money(total("pendingBalance"), summaryCurrency)}</strong><small>Held or in flight</small></article>
      </section>

      <section className="panel account-create">
        <div className="panelHeader">
          <h2>Open an account</h2>
        </div>
        <div className="panelBody">
          <form className="row" onSubmit={create}>
            <div>
              <label htmlFor="ccy" style={{ marginTop: 0 }}>Currency</label>
              <input id="ccy" value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} style={{ width: 110 }} maxLength={3} />
            </div>
            <div>
              <label htmlFor="open" style={{ marginTop: 0 }}>Opening balance</label>
              <input id="open" value={opening} onChange={(e) => setOpening(e.target.value)} inputMode="decimal" style={{ width: 160 }} />
            </div>
            <button type="submit">Create account</button>
          </form>
          {error && <p className="error">{error}</p>}
        </div>
      </section>

      <section className="panel account-list" style={{ marginTop: 18 }}>
        <table>
          <thead>
            <tr>
              <th>Account</th>
              <th>Currency</th>
              <th className="num">Available</th>
              <th className="num">Pending</th>
              <th className="num">Posted</th>
              <th>Status</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {accounts === null && <SkeletonRows cols={7} />}
            {accounts?.map((a) => (
              <tr key={a.id}>
                <td className="mono">{shortId(a.id)}</td>
                <td>{a.currency}</td>
                <td className="num amount">{money(a.availableBalance, a.currency)}</td>
                <td className="num amount">{money(a.pendingBalance, a.currency)}</td>
                <td className="num amount">{money(a.postedBalance, a.currency)}</td>
                <td><StatusPill value={a.status} /></td>
                <td><Link href="/ledger">Ledger →</Link></td>
              </tr>
            ))}
          </tbody>
        </table>
        {accounts !== null && accounts.length === 0 && (
          <EmptyState title="No accounts yet" hint="Open a funded account above, then create your first transfer." />
        )}
      </section>
    </Shell>
  );
}
