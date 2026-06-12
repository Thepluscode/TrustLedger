"use client";

import { useEffect, useMemo, useState, type FormEvent } from "react";
import Shell from "../components/Shell";
import { RiskBadge, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import { money, shortId } from "../lib/format";
import type { AccountView, AssessResponse, BeneficiaryView, ExternalPaymentResponse, TransferResponse } from "../lib/types";

/** §8.3 multi-step create flow: details → risk preview → confirm → result. */
const STEPS = ["Details", "Risk preview", "Confirm"] as const;

export default function TransfersPage() {
  const [accounts, setAccounts] = useState<AccountView[]>([]);
  const [beneficiaries, setBeneficiaries] = useState<BeneficiaryView[]>([]);
  const [step, setStep] = useState(0);

  const [source, setSource] = useState("");
  const [destination, setDestination] = useState("");
  const [beneficiaryId, setBeneficiaryId] = useState("");
  const [amount, setAmount] = useState("100.00");
  const [reference, setReference] = useState("");

  const [assess, setAssess] = useState<AssessResponse | null>(null);
  const [assessing, setAssessing] = useState(false);
  const [result, setResult] = useState<TransferResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // External sandbox rail (unchanged behaviour, restyled)
  const [extAmount, setExtAmount] = useState("200.00");
  const [extScenario, setExtScenario] = useState("success");
  const [extResult, setExtResult] = useState<ExternalPaymentResponse | null>(null);
  const [extError, setExtError] = useState<string | null>(null);

  useEffect(() => {
    api
      .listAccounts()
      .then((a) => {
        setAccounts(a);
        if (a[0]) setSource(a[0].id);
        if (a[1]) setDestination(a[1].id);
      })
      .catch((e) => setError((e as Error).message));
    api.listBeneficiaries().then(setBeneficiaries).catch(() => {});
  }, []);

  const sourceAccount = useMemo(() => accounts.find((a) => a.id === source), [accounts, source]);
  const beneficiary = useMemo(() => beneficiaries.find((b) => b.id === beneficiaryId), [beneficiaries, beneficiaryId]);
  const overBalance =
    sourceAccount !== undefined && parseFloat(amount) > parseFloat(sourceAccount.availableBalance);

  async function toRiskPreview() {
    setError(null);
    setAssessing(true);
    setStep(1);
    try {
      setAssess(await api.assessRisk("web-device", destination, amount));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setAssessing(false);
    }
  }

  async function submit() {
    setError(null);
    setBusy(true);
    try {
      const res = await api.createTransfer(crypto.randomUUID(), {
        sourceAccountId: source,
        destinationAccountId: destination,
        beneficiaryId: beneficiaryId || crypto.randomUUID(),
        amount,
        currency: sourceAccount?.currency ?? "GBP",
        reference: reference || "console transfer",
        deviceId: "web-device",
        currentCountry: "GB",
      });
      setResult(res);
      api.listAccounts().then(setAccounts).catch(() => {});
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function reset() {
    setResult(null);
    setAssess(null);
    setStep(0);
    setReference("");
  }

  async function submitExternal(e: FormEvent) {
    e.preventDefault();
    setExtError(null);
    setExtResult(null);
    try {
      setExtResult(
        await api.createExternalTransfer(crypto.randomUUID(), {
          sourceAccountId: source,
          beneficiaryId: crypto.randomUUID(),
          amount: extAmount,
          currency: "GBP",
          reference: "ui external",
          deviceId: "web",
          currentCountry: "GB",
          scenario: extScenario,
        }),
      );
    } catch (err) {
      setExtError((err as Error).message);
    }
  }

  const accountOption = (a: AccountView) =>
    `${shortId(a.id)} — ${money(a.availableBalance, a.currency)} available`;

  return (
    <Shell active="/transfers">
      <header className="topbar">
        <div>
          <p className="eyebrow">Money Movement</p>
          <h1>Create transfer</h1>
          <p className="sub">Every transfer is fraud-scored before it posts; the ledger entry is the source of truth.</p>
        </div>
      </header>

      {/* §22.1 success screen */}
      {result ? (
        <section className="panel">
          <div className="panelBody">
            <h2>
              {result.status === "COMPLETED"
                ? "Transfer completed"
                : result.status === "HELD_FOR_REVIEW"
                  ? "Transfer held for review"
                  : `Transfer ${result.status.replace(/_/g, " ").toLowerCase()}`}
            </h2>
            <p className="row" style={{ gap: 10, alignItems: "center" }}>
              <StatusPill value={result.status} /> <RiskBadge score={result.riskScore} />
              <span className="mono muted">{shortId(result.transactionId)}</span>
            </p>
            <p className="muted">{result.message}</p>
            {result.status === "COMPLETED" && (
              <div className="notice">
                <b>Ledger transaction balanced</b> — debit and credit posted atomically. <b>Audit log created.</b>
              </div>
            )}
            {result.status === "HELD_FOR_REVIEW" && (
              <div className="notice warn">
                <b>Funds reserved, not moved.</b> A fraud case has been opened for analyst review — approve or
                reject it from the Cases queue. Decision {result.decision}.
              </div>
            )}
            <div className="row" style={{ marginTop: 16 }}>
              <button onClick={reset}>New transfer</button>
              <a className="btn secondary" href="/fraud-cases" style={{ textDecoration: "none" }}>
                Open case queue
              </a>
            </div>
          </div>
        </section>
      ) : (
        <section className="panel">
          <div className="panelBody">
            <div className="steps" aria-label="Transfer steps">
              {STEPS.map((s, i) => (
                <span key={s} className={`step${i === step ? " current" : i < step ? " done" : ""}`}>
                  {i + 1}. {s}
                </span>
              ))}
            </div>

            {step === 0 && (
              <form
                className="form"
                onSubmit={(e) => {
                  e.preventDefault();
                  toRiskPreview();
                }}
              >
                <label htmlFor="src">Source account</label>
                <select id="src" value={source} onChange={(e) => setSource(e.target.value)}>
                  {accounts.map((a) => (
                    <option key={a.id} value={a.id}>
                      {accountOption(a)}
                    </option>
                  ))}
                </select>
                <label htmlFor="dst">Destination account</label>
                <select id="dst" value={destination} onChange={(e) => setDestination(e.target.value)}>
                  {accounts.map((a) => (
                    <option key={a.id} value={a.id}>
                      {accountOption(a)}
                    </option>
                  ))}
                </select>
                {beneficiaries.length > 0 && (
                  <>
                    <label htmlFor="ben">Beneficiary</label>
                    <select id="ben" value={beneficiaryId} onChange={(e) => setBeneficiaryId(e.target.value)}>
                      <option value="">New / ad-hoc beneficiary (untrusted)</option>
                      {beneficiaries.map((b) => (
                        <option key={b.id} value={b.id}>
                          {b.name} {b.trusted ? "· trusted" : "· not yet trusted"}
                        </option>
                      ))}
                    </select>
                  </>
                )}
                <label htmlFor="amt">Amount {sourceAccount ? `(${sourceAccount.currency})` : ""}</label>
                <input id="amt" value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" />
                {sourceAccount && (
                  <p className={overBalance ? "error" : "hint"} style={{ margin: "4px 0 0" }}>
                    Available: {money(sourceAccount.availableBalance, sourceAccount.currency)}
                    {overBalance && " — amount exceeds available balance"}
                  </p>
                )}
                <label htmlFor="ref">Reference</label>
                <input id="ref" value={reference} onChange={(e) => setReference(e.target.value)} placeholder="invoice 1042" />
                <div className="row" style={{ marginTop: 16 }}>
                  <button type="submit" disabled={!source || !destination || source === destination}>
                    Preview risk →
                  </button>
                </div>
                {source === destination && source !== "" && (
                  <p className="hint">Source and destination must differ.</p>
                )}
              </form>
            )}

            {step === 1 && (
              <div>
                <h2>Risk preview</h2>
                {assessing && <div className="skeleton" style={{ maxWidth: 380, minHeight: 22 }} />}
                {assess && (
                  <>
                    <p className="row" style={{ gap: 10, alignItems: "center" }}>
                      <RiskBadge score={assess.riskScore} />
                      <StatusPill value={assess.decision} />
                    </p>
                    <p className="muted" style={{ marginBottom: 4 }}>Why:</p>
                    {assess.signals.length ? (
                      <ul style={{ margin: "4px 0 0", paddingLeft: 20 }}>
                        {assess.signals.map((s) => (
                          <li key={s}>{s.replace(/_/g, " ").toLowerCase()}</li>
                        ))}
                      </ul>
                    ) : (
                      <p className="muted">No risk signals — looks like a routine transfer.</p>
                    )}
                    {assess.decision !== "ALLOW" && (
                      <div className="notice warn" style={{ marginTop: 14 }}>
                        This transfer is likely to be <b>{assess.decision.replace(/_/g, " ").toLowerCase()}</b> when
                        submitted — it may require MFA or be held for analyst review.
                      </div>
                    )}
                  </>
                )}
                {error && <p className="error">{error}</p>}
                <div className="row" style={{ marginTop: 16 }}>
                  <button className="secondary" onClick={() => setStep(0)}>← Edit details</button>
                  <button onClick={() => setStep(2)} disabled={assessing}>Continue →</button>
                </div>
              </div>
            )}

            {step === 2 && (
              <div>
                <h2>Confirm</h2>
                <div className="entry"><span className="muted">From</span><span className="mono">{shortId(source)}</span></div>
                <div className="entry"><span className="muted">To</span><span className="mono">{shortId(destination)}</span></div>
                <div className="entry">
                  <span className="muted">Beneficiary</span>
                  <span>{beneficiary ? `${beneficiary.name}${beneficiary.trusted ? " (trusted)" : ""}` : "ad-hoc (untrusted)"}</span>
                </div>
                <div className="entry">
                  <span className="muted">Amount</span>
                  <span className="amt">{money(amount, sourceAccount?.currency ?? "GBP")}</span>
                </div>
                {assess && (
                  <div className="entry">
                    <span className="muted">Estimated risk</span>
                    <span><RiskBadge score={assess.riskScore} /></span>
                  </div>
                )}
                <p className="hint" style={{ marginTop: 10 }}>
                  Submitted with a client-generated idempotency key — a network retry can never double-spend.
                </p>
                {error && <p className="error">{error}</p>}
                <div className="row" style={{ marginTop: 16 }}>
                  <button className="secondary" onClick={() => setStep(1)}>← Back</button>
                  <button onClick={submit} disabled={busy}>
                    {busy ? "Submitting…" : "Send transfer"}
                  </button>
                </div>
              </div>
            )}
          </div>
        </section>
      )}

      <section className="panel" style={{ marginTop: 18 }}>
        <div className="panelHeader">
          <div>
            <h2>External payment (sandbox rail)</h2>
            <p className="sub">Funds reserve on submit; a webhook or reconciliation settles or releases. No real money moves.</p>
          </div>
        </div>
        <div className="panelBody">
          <form className="form" onSubmit={submitExternal}>
            <label htmlFor="ext-amt">Amount (GBP)</label>
            <input id="ext-amt" value={extAmount} onChange={(e) => setExtAmount(e.target.value)} inputMode="decimal" />
            <label htmlFor="ext-scn">Provider scenario</label>
            <select id="ext-scn" value={extScenario} onChange={(e) => setExtScenario(e.target.value)}>
              <option value="success">success — settles via webhook</option>
              <option value="slow">slow — pending settlement</option>
              <option value="fail">fail — released immediately</option>
              <option value="timeout">timeout — PENDING_UNKNOWN → reconciliation</option>
            </select>
            <div className="row" style={{ marginTop: 14 }}>
              <button type="submit" className="secondary">Submit external payment</button>
            </div>
          </form>
          {extError && <p className="error">{extError}</p>}
          {extResult && (
            <div style={{ marginTop: 12 }}>
              <p className="row" style={{ gap: 10, alignItems: "center" }}>
                <StatusPill value={extResult.status} />
                <span className="mono muted">{extResult.providerReference ?? "no provider ref"}</span>
              </p>
              <p className="muted">{extResult.message}</p>
              {extResult.status === "PENDING_UNKNOWN" && (
                <div className="notice warn">
                  <b>The provider did not confirm final status.</b> TrustLedger has not retried the payment
                  blindly — reconciliation will check the provider before taking further action.
                </div>
              )}
            </div>
          )}
        </div>
      </section>
    </Shell>
  );
}
