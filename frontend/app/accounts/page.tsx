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

  const accountCurrency = accounts && accounts.length > 0 && accounts.every((account) => account.currency === accounts[0].currency)
    ? accounts[0].currency
    : null;
  const totals = accounts && accountCurrency ? accounts.reduce((sum, account) => ({
    available: sum.available + Number(account.availableBalance),
    pending: sum.pending + Number(account.pendingBalance),
    posted: sum.posted + Number(account.postedBalance),
  }), { available: 0, pending: 0, posted: 0 }) : null;

  return (
    <Shell active="/accounts">
      <header className="topbar">
        <div>
          <p className="eyebrow">Money</p>
          <h1>Accounts</h1>
          <p className="sub">Available is spendable now; pending is reserved by holds or in-flight external payments.</p>
        </div>
      </header>

      <section className="account-summary" aria-label="Account balance summary">
        <article className="card"><span>Accounts</span><strong>{accounts?.length ?? "—"}</strong><small>Tenant-scoped records</small></article>
        <article className="card"><span>Available</span><strong>{totals && accountCurrency ? money(totals.available, accountCurrency) : "By account"}</strong><small>Spendable now</small></article>
        <article className="card"><span>Reserved</span><strong>{totals && accountCurrency ? money(totals.pending, accountCurrency) : "By account"}</strong><small>Held or in flight</small></article>
      </section>

      <div className="account-workspace">
      <section className="panel account-create-panel" id="open-account">
        <div className="panelHeader">
          <h2>Open an account</h2>
        </div>
        <div className="panelBody">
          <form className="form" onSubmit={create}>
            <div>
              <label htmlFor="ccy" style={{ marginTop: 0 }}>Currency</label>
              <input id="ccy" value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} maxLength={3} />
            </div>
            <div>
              <label htmlFor="open" style={{ marginTop: 0 }}>Opening balance</label>
              <input id="open" value={opening} onChange={(e) => setOpening(e.target.value)} inputMode="decimal" />
            </div>
            <button type="submit">Create account</button>
          </form>
          <p className="hint">Accounts organise balances by currency. Financial truth remains in the ledger.</p>
          {error && <p className="error">{error}</p>}
        </div>
      </section>

      <section className="panel account-list-panel">
        <div className="panelHeader"><div><h2>All accounts</h2><p className="sub">Available, reserved and posted balances remain visibly separate.</p></div></div>
        <table className="desktop-table">
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
        <div className="mobile-record-list">
          {accounts?.map((account) => (
            <article className="mobile-record account-record" key={account.id}>
              <div className="record-head"><span className="record-icon">£</span><div><small>Account</small><strong className="mono">{shortId(account.id)}</strong></div><StatusPill value={account.status} /></div>
              <div className="record-stats"><span><small>Available</small><b>{money(account.availableBalance, account.currency)}</b></span><span><small>Reserved</small><b>{money(account.pendingBalance, account.currency)}</b></span><span><small>Posted</small><b>{money(account.postedBalance, account.currency)}</b></span></div>
              <Link href="/ledger">Open ledger →</Link>
            </article>
          ))}
        </div>
        {accounts !== null && accounts.length === 0 && (
          <EmptyState title="No accounts yet" hint="Open a funded account above, then create your first transfer." />
        )}
      </section>
      </div>
      <a href="#open-account" className="mobile-primary-action">＋ Open account</a>
    </Shell>
  );
}
