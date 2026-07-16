"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { type FormEvent, useEffect, useState } from "react";
import Shell from "../../components/Shell";
import { RiskBadge, StatusPill } from "../../components/ui";
import { api } from "../../lib/api";
import { dateTime, money, shortId } from "../../lib/format";
import type { LedgerTransactionView, TransferDetail } from "../../lib/types";

/** §8.4 visual state machine — the lifecycle position derived from the stored status. */
function lifecycle(status: string): { label: string; cls: string }[] {
  const created = { label: "Created", cls: "done" };
  const checked = { label: "Fraud checked", cls: "done" };
  switch (status) {
    case "COMPLETED":
      return [created, checked, { label: "Posted", cls: "done" }, { label: "Completed", cls: "current" }];
    case "MFA_REQUIRED":
      return [created, checked, { label: "Step-up required", cls: "current" }, { label: "Completed", cls: "" }];
    case "ACTION_REQUIRED":
      return [created, checked, { label: "Submitted", cls: "done" }, { label: "Provider OTP required", cls: "current" },
        { label: "Settled", cls: "" }];
    case "HELD_FOR_REVIEW":
      return [created, checked, { label: "Held for review", cls: "current" }, { label: "Completed", cls: "" }];
    case "PENDING_SETTLEMENT":
    case "PENDING_UNKNOWN":
      return [created, checked, { label: "Submitted", cls: "done" },
        { label: status.replace(/_/g, " ").toLowerCase(), cls: "current" }, { label: "Settled", cls: "" }];
    case "REJECTED":
      return [created, checked, { label: "Rejected", cls: "failed" }];
    case "FAILED":
      return [created, checked, { label: "Failed", cls: "failed" }];
    default:
      return [created, checked, { label: status.replace(/_/g, " ").toLowerCase(), cls: "current" }];
  }
}

function LedgerSplit({ tx }: { tx: LedgerTransactionView }) {
  const debits = tx.entries.filter((e) => e.direction === "DEBIT");
  const credits = tx.entries.filter((e) => e.direction === "CREDIT");
  const sum = (xs: typeof tx.entries) => xs.reduce((a, e) => a + parseFloat(e.amount), 0);
  const dt = sum(debits);
  const ct = sum(credits);
  const balanced = Math.abs(dt - ct) < 0.0001;
  return (
    <div>
      <div className="split">
        <div>
          <h3>Debit side</h3>
          {debits.map((e) => (
            <div className="entry" key={e.id}>
              <span><span className="mono">{shortId(e.accountId)}</span> <span className="muted">{e.entryType.replace(/_/g, " ").toLowerCase()}</span></span>
              <span className="amt">{money(e.amount, e.currency)}</span>
            </div>
          ))}
        </div>
        <div>
          <h3>Credit side</h3>
          {credits.map((e) => (
            <div className="entry" key={e.id}>
              <span><span className="mono">{shortId(e.accountId)}</span> <span className="muted">{e.entryType.replace(/_/g, " ").toLowerCase()}</span></span>
              <span className="amt">{money(e.amount, e.currency)}</span>
            </div>
          ))}
        </div>
      </div>
      <div className="balanced">
        <span>Debits: <b className="amount">{money(dt, tx.currency)}</b></span>
        <span>Credits: <b className="amount">{money(ct, tx.currency)}</b></span>
        {balanced ? <span className="ok">✓ Balanced</span> : <span className="bad">✗ UNBALANCED</span>}
      </div>
    </div>
  );
}

export default function TransferDetailPage() {
  const params = useParams<{ transactionId: string }>();
  const id = params.transactionId;
  const [data, setData] = useState<TransferDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [otp, setOtp] = useState("");
  const [otpSubmitting, setOtpSubmitting] = useState(false);
  const [otpError, setOtpError] = useState<string | null>(null);
  const [otpNotice, setOtpNotice] = useState<string | null>(null);

  const reload = async () => {
    if (!id) return;
    setData(await api.getTransfer(id));
  };

  useEffect(() => {
    reload().catch((e) => setError((e as Error).message));
  }, [id]);

  const submitPaystackOtp = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const writeOnlyOtp = otp;
    setOtp("");
    setOtpError(null);
    setOtpNotice(null);
    setOtpSubmitting(true);
    try {
      const result = await api.finalizePaystackOtp(id, writeOnlyOtp);
      setOtpNotice(`Paystack accepted the action. Current status: ${result.status.replace(/_/g, " ").toLowerCase()}.`);
      await reload();
    } catch (e) {
      setOtpError((e as Error).message);
      await reload().catch(() => undefined);
    } finally {
      setOtpSubmitting(false);
    }
  };

  const t = data?.transfer;

  return (
    <Shell active="/transfers">
      <header className="topbar">
        <div>
          <p className="eyebrow"><Link href="/transfers">Transfers</Link> / detail</p>
          <h1>Transfer {shortId(id)}</h1>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {!data && !error && <div className="skeleton" style={{ maxWidth: 480, minHeight: 24 }} />}

      {t && (
        <>
          <section className="panel">
            <div className="panelBody">
              <p className="row" style={{ gap: 10, alignItems: "center" }}>
                <StatusPill value={t.status} /> <RiskBadge score={t.riskScore} />
                <span className="muted">decision {t.fraudDecision.replace(/_/g, " ").toLowerCase()}</span>
                <span className="badge">{t.channel.toLowerCase()}</span>
              </p>
              <div className="statemachine" style={{ padding: "14px 0" }}>
                {lifecycle(t.status).map((s, i, arr) => (
                  <span key={i} style={{ display: "inline-flex", alignItems: "center" }}>
                    <span className={`state ${s.cls}`}>{s.label}</span>
                    {i < arr.length - 1 && <span className="arrow">→</span>}
                  </span>
                ))}
              </div>
              <div style={{ maxWidth: 520 }}>
                <div className="entry"><span className="muted">Amount</span><span className="amt">{money(t.amount, t.currency)}</span></div>
                <div className="entry"><span className="muted">From</span><span className="mono">{shortId(t.sourceAccountId)}</span></div>
                <div className="entry"><span className="muted">To</span><span className="mono">{shortId(t.destinationAccountId)}</span></div>
                <div className="entry"><span className="muted">Reference</span><span>{t.reference || "—"}</span></div>
                <div className="entry"><span className="muted">Created</span><span>{dateTime(t.createdAt)}</span></div>
              </div>
            </div>
          </section>

          {t.status === "ACTION_REQUIRED" && (
            <section className="panel" style={{ marginTop: 18 }}>
              <div className="panelHeader">
                <div>
                  <h2>Paystack verification</h2>
                  <p className="sub">Enter the one-time code supplied for this payout. The code is submitted once and is not stored.</p>
                </div>
              </div>
              <form className="panelBody" onSubmit={submitPaystackOtp}>
                <label htmlFor="paystack-otp" style={{ marginTop: 0 }}>One-time code</label>
                <div className="row" style={{ gap: 10, alignItems: "end", maxWidth: 420 }}>
                  <input
                    id="paystack-otp"
                    type="password"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    pattern="[0-9]{4,10}"
                    minLength={4}
                    maxLength={10}
                    value={otp}
                    onChange={(event) => setOtp(event.target.value.replace(/\D/g, ""))}
                    disabled={otpSubmitting}
                    required
                    aria-describedby="paystack-otp-help"
                  />
                  <button className="btn" type="submit" disabled={otpSubmitting || otp.length < 4}>
                    {otpSubmitting ? "Submitting…" : "Submit code"}
                  </button>
                </div>
                <p id="paystack-otp-help" className="muted" style={{ marginTop: 8 }}>
                  This value remains only in this form until submission and is cleared immediately.
                </p>
                {otpError && <p className="error">{otpError}</p>}
                {otpNotice && <p className="ok">{otpNotice}</p>}
              </form>
            </section>
          )}

          {data.fraudCase && (
            <section className="panel" style={{ marginTop: 18 }}>
              <div className="panelHeader">
                <div><h2>Fraud case</h2></div>
                <Link href="/fraud-cases">Case queue →</Link>
              </div>
              <div className="panelBody">
                <p className="row" style={{ gap: 10, alignItems: "center" }}>
                  <span className="mono">{shortId(data.fraudCase.id)}</span>
                  <StatusPill value={data.fraudCase.status} />
                  <RiskBadge score={data.fraudCase.riskScore} />
                  <span className="muted">{data.fraudCase.severity}</span>
                </p>
              </div>
            </section>
          )}

          <section className="panel" style={{ marginTop: 18 }}>
            <div className="panelHeader"><div><h2>Ledger</h2><p className="sub">The posted double-entry movement(s).</p></div></div>
            {data.ledger.length === 0 ? (
              <div className="panelBody"><p className="muted">Nothing posted yet — funds are reserved or the transfer hasn&apos;t settled.</p></div>
            ) : (
              data.ledger.map((tx) => (
                <div key={tx.id} style={{ borderTop: "1px solid var(--line)" }}>
                  <p className="muted" style={{ padding: "10px 18px 0" }}>
                    <span className="mono">{shortId(tx.id)}</span> · {tx.type.replace(/_/g, " ").toLowerCase()} · <StatusPill value={tx.status} />
                  </p>
                  <LedgerSplit tx={tx} />
                </div>
              ))
            )}
          </section>

          <section className="panel" style={{ marginTop: 18 }}>
            <div className="panelHeader"><div><h2>Audit trail</h2><p className="sub">Every recorded action on this transfer.</p></div></div>
            <div className="panelBody">
              {data.auditTrail.length === 0 ? (
                <p className="muted">No audit entries.</p>
              ) : (
                <ul className="timeline">
                  {data.auditTrail.map((a) => (
                    <li key={a.id} className={/REJECT|HELD|FAIL/.test(a.action) ? "hot" : ""}>
                      <div>{a.action.replace(/_/g, " ").toLowerCase()}</div>
                      <div className="when">{dateTime(a.createdAt)} · {a.actorType.toLowerCase()}</div>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </section>
        </>
      )}
    </Shell>
  );
}
