"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Shell from "../components/Shell";
import { EmptyState, SkeletonRows, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import { money, shortId } from "../lib/format";
import type { AccountView, LedgerEntryView, LedgerTransactionView } from "../lib/types";

/**
 * Ledger explorer (design.md §9) — pick an account, walk its double-entry history,
 * open any ledger transaction as a side-by-side debit/credit split with the balanced invariant.
 */
export default function LedgerPage() {
  const [accounts, setAccounts] = useState<AccountView[]>([]);
  const [accountId, setAccountId] = useState("");
  const [entries, setEntries] = useState<LedgerEntryView[] | null>(null);
  const [tx, setTx] = useState<LedgerTransactionView | null>(null);
  const [txLoading, setTxLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .listAccounts()
      .then((a) => {
        setAccounts(a);
        if (a[0]) setAccountId(a[0].id);
      })
      .catch((e) => setError((e as Error).message));
  }, []);

  const loadEntries = useCallback((id: string) => {
    setEntries(null);
    setTx(null);
    api.accountLedger(id).then(setEntries).catch((e) => setError((e as Error).message));
  }, []);

  useEffect(() => {
    if (accountId) loadEntries(accountId);
  }, [accountId, loadEntries]);

  async function openTransaction(id: string) {
    setTxLoading(true);
    setError(null);
    try {
      setTx(await api.ledgerTransaction(id));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setTxLoading(false);
    }
  }

  const account = accounts.find((a) => a.id === accountId);

  // §9.3 invariant: sum(debits) must equal sum(credits).
  const split = useMemo(() => {
    if (!tx) return null;
    const debits = tx.entries.filter((e) => e.direction === "DEBIT");
    const credits = tx.entries.filter((e) => e.direction === "CREDIT");
    const sum = (xs: LedgerEntryView[]) => xs.reduce((acc, e) => acc + parseFloat(e.amount), 0);
    const debitTotal = sum(debits);
    const creditTotal = sum(credits);
    return { debits, credits, debitTotal, creditTotal, balanced: Math.abs(debitTotal - creditTotal) < 0.0001 };
  }, [tx]);

  return (
    <Shell active="/ledger">
      <header className="topbar">
        <div>
          <p className="eyebrow">Ledger</p>
          <h1>Ledger explorer</h1>
          <p className="sub">
            The double-entry ledger is the source of truth — every money movement is a balanced debit/credit pair.
          </p>
        </div>
        <div style={{ minWidth: 280 }}>
          <label htmlFor="acct" style={{ marginTop: 0 }}>Account</label>
          <select id="acct" value={accountId} onChange={(e) => setAccountId(e.target.value)}>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {shortId(a.id)} — {money(a.availableBalance, a.currency)} available
              </option>
            ))}
          </select>
        </div>
      </header>
      {error && <p className="error">{error}</p>}

      <section className="panel" aria-label="Ledger entries">
        <div className="panelHeader">
          <div>
            <h2>Entries</h2>
            <p className="sub">
              {account
                ? `Posted: ${money(account.postedBalance, account.currency)} · pending: ${money(account.pendingBalance, account.currency)}`
                : "Pick an account."}
            </p>
          </div>
        </div>
        <table>
          <thead>
            <tr>
              <th>Direction</th>
              <th>Type</th>
              <th className="num">Amount</th>
              <th>Ledger transaction</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {entries === null && <SkeletonRows cols={5} />}
            {entries?.map((e) => (
              <tr key={e.id}>
                <td>
                  <span className={`pill ${e.direction === "DEBIT" ? "bad" : "ok"}`}>{e.direction}</span>
                </td>
                <td className="muted">{e.entryType.replace(/_/g, " ").toLowerCase()}</td>
                <td className="num amount">
                  {e.direction === "DEBIT" ? "−" : "+"}
                  {money(e.amount, e.currency)}
                </td>
                <td className="mono">{shortId(e.ledgerTransactionId)}</td>
                <td>
                  <button className="ghost" onClick={() => openTransaction(e.ledgerTransactionId)}>
                    Inspect →
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {entries !== null && entries.length === 0 && (
          <EmptyState
            title="No ledger entries for this account"
            hint="Create a transfer from or to this account — completed transfers post balanced debit/credit pairs here."
          />
        )}
      </section>

      {(txLoading || tx) && (
        <section className="panel" style={{ marginTop: 18 }} aria-label="Ledger transaction detail">
          <div className="panelHeader">
            <div>
              <h2>Ledger transaction {tx ? shortId(tx.id) : ""}</h2>
              {tx && (
                <p className="sub row" style={{ gap: 8, alignItems: "center" }}>
                  <span className="muted">{tx.type.replace(/_/g, " ").toLowerCase()}</span>
                  <StatusPill value={tx.status} />
                </p>
              )}
            </div>
            <button className="ghost" onClick={() => setTx(null)}>Close</button>
          </div>
          {txLoading && (
            <div className="panelBody">
              <div className="skeleton" style={{ minHeight: 60 }} />
            </div>
          )}
          {tx && split && (
            <>
              {/* §9.4 side-by-side debit/credit split */}
              <div className="split">
                <div>
                  <h3>Debit side</h3>
                  {split.debits.map((e) => (
                    <div className="entry" key={e.id}>
                      <span>
                        <span className="mono">{shortId(e.accountId)}</span>{" "}
                        <span className="muted">{e.entryType.replace(/_/g, " ").toLowerCase()}</span>
                      </span>
                      <span className="amt">{money(e.amount, e.currency)}</span>
                    </div>
                  ))}
                  {split.debits.length === 0 && <p className="muted">No debit entries.</p>}
                </div>
                <div>
                  <h3>Credit side</h3>
                  {split.credits.map((e) => (
                    <div className="entry" key={e.id}>
                      <span>
                        <span className="mono">{shortId(e.accountId)}</span>{" "}
                        <span className="muted">{e.entryType.replace(/_/g, " ").toLowerCase()}</span>
                      </span>
                      <span className="amt">{money(e.amount, e.currency)}</span>
                    </div>
                  ))}
                  {split.credits.length === 0 && <p className="muted">No credit entries.</p>}
                </div>
              </div>
              {/* §9.3 invariant strip */}
              <div className="balanced">
                <span>Debits: <b className="amount">{money(split.debitTotal, tx.currency)}</b></span>
                <span>Credits: <b className="amount">{money(split.creditTotal, tx.currency)}</b></span>
                {split.balanced ? (
                  <span className="ok">✓ Balanced</span>
                ) : (
                  <span className="bad">✗ UNBALANCED — raise a reconciliation issue</span>
                )}
              </div>
            </>
          )}
        </section>
      )}
    </Shell>
  );
}
