"use client";

import { Fragment, useEffect, useState, type FormEvent } from "react";
import Shell from "../components/Shell";
import { EmptyState, SkeletonRows, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import { shortId } from "../lib/format";
import type { BeneficiaryView, PayoutInstrumentView, ProviderConfigView, ProviderRecipientView } from "../lib/types";

/** The backend accepts exactly these two; anything else is rejected as an invalid instrument type. */
const INSTRUMENT_TYPES = ["BANK_ACCOUNT", "MOBILE_MONEY"] as const;
/** Only these can be set administratively — activation is not a tenant-side action. */
const SETTABLE_STATUSES = ["SUSPENDED", "REVOKED"] as const;

const EMPTY_FORM = {
  instrumentType: INSTRUMENT_TYPES[0] as string,
  country: "",
  currency: "",
  accountName: "",
  bankCode: "",
  maskedIdentifier: "",
  externalReference: "",
};

export default function BeneficiariesPage() {
  const [beneficiaries, setBeneficiaries] = useState<BeneficiaryView[] | null>(null);
  const [configs, setConfigs] = useState<ProviderConfigView[]>([]);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [instruments, setInstruments] = useState<PayoutInstrumentView[] | null>(null);
  const [recipients, setRecipients] = useState<Record<string, ProviderRecipientView[]>>({});
  const [form, setForm] = useState({ ...EMPTY_FORM });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [newName, setNewName] = useState("");
  const [newAccountId, setNewAccountId] = useState("");

  useEffect(() => {
    api.listBeneficiaries().then(setBeneficiaries).catch((e) => setError((e as Error).message));
    // Provider configs are needed to register a recipient; a failure here shouldn't blank the page.
    api.listProviderConfigs().then(setConfigs).catch(() => setConfigs([]));
  }, []);

  function openBeneficiary(id: string): void {
    if (expanded === id) {
      setExpanded(null);
      return;
    }
    setExpanded(id);
    setInstruments(null);
    setForm({ ...EMPTY_FORM });
    api
      .listPayoutInstruments(id)
      .then(setInstruments)
      .catch((e) => {
        setInstruments([]);
        setError((e as Error).message);
      });
  }

  /** Runs an action and re-reads instruments, so a server-side refusal shows instead of an optimistic row. */
  async function run(beneficiaryId: string, message: string, action: () => Promise<unknown>): Promise<void> {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
      setNotice(message);
      setInstruments(await api.listPayoutInstruments(beneficiaryId));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function createInstrument(event: FormEvent, beneficiaryId: string): Promise<void> {
    event.preventDefault();
    await run(beneficiaryId, `Instrument created for ${shortId(beneficiaryId)} — pending verification.`, () =>
      api.createPayoutInstrument(beneficiaryId, {
        ...form,
        country: form.country.toUpperCase(),
        currency: form.currency.toUpperCase(),
      }),
    );
    setForm({ ...EMPTY_FORM });
  }

  function loadRecipients(beneficiaryId: string, instrumentId: string): void {
    api
      .listProviderRecipients(beneficiaryId, instrumentId)
      .then((r) => setRecipients((prev) => ({ ...prev, [instrumentId]: r })))
      .catch((e) => setError((e as Error).message));
  }

  async function registerRecipient(
    beneficiaryId: string,
    instrumentId: string,
    configId: string,
    code: string,
  ): Promise<void> {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await api.registerProviderRecipient(beneficiaryId, instrumentId, configId, code);
      setNotice("Provider recipient registered.");
      loadRecipients(beneficiaryId, instrumentId);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const bankAccount = form.instrumentType === "BANK_ACCOUNT";

  return (
    <Shell active="/beneficiaries">
      <header className="topbar">
        <div>
          <p className="eyebrow">Money</p>
          <h1>Beneficiaries &amp; payout instruments</h1>
          <p className="sub">
            An instrument is where an external payout actually lands. A beneficiary without a verified instrument
            cannot be paid.
          </p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {notice && <p className="notice">{notice}</p>}

      <section className="panel">
        <table>
          <thead>
            <tr>
              <th>Beneficiary</th>
              <th>Destination account</th>
              <th>Trusted</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {beneficiaries === null && <SkeletonRows cols={4} />}
            {beneficiaries?.map((b) => (
              <Fragment key={b.id}>
                <tr>
                  <td>{b.name}</td>
                  <td className="mono">{shortId(b.destinationAccountId)}</td>
                  <td><StatusPill value={b.trusted ? "TRUSTED" : "UNTRUSTED"} /></td>
                  <td className="row-actions">
                    <button className="secondary" onClick={() => openBeneficiary(b.id)}>
                      {expanded === b.id ? "Hide instruments" : "Payout instruments"}
                    </button>
                  </td>
                </tr>
                {expanded === b.id && (
                  <tr>
                    <td colSpan={4} style={{ background: "var(--surface-2, rgba(0,0,0,0.03))" }}>
                      {instruments === null && <span className="muted">Loading instruments…</span>}
                      {instruments?.length === 0 && (
                        <p className="muted">No payout instruments yet — add one below.</p>
                      )}

                      {instruments && instruments.length > 0 && (
                        <table>
                          <thead>
                            <tr>
                              <th>Type</th>
                              <th>Account name</th>
                              <th>Identifier</th>
                              <th>Corridor</th>
                              <th>Status</th>
                              <th></th>
                            </tr>
                          </thead>
                          <tbody>
                            {instruments.map((i) => (
                              <Fragment key={i.id}>
                                <tr>
                                  <td>{i.instrumentType.replace(/_/g, " ").toLowerCase()}</td>
                                  <td>{i.accountName}</td>
                                  <td className="mono muted">{i.maskedIdentifier}</td>
                                  <td className="mono">{i.country} · {i.currency}</td>
                                  <td>
                                    <StatusPill value={i.status} />
                                    {!i.verified && <span className="muted"> unverified</span>}
                                  </td>
                                  <td className="row-actions">
                                    <button
                                      className="secondary"
                                      onClick={() => loadRecipients(b.id, i.id)}
                                      disabled={!i.verified}
                                      title={
                                        i.verified
                                          ? "Provider recipient mappings"
                                          : "Provider registration requires a verified instrument"
                                      }
                                    >
                                      Providers
                                    </button>
                                    {SETTABLE_STATUSES.map((s) => (
                                      <button
                                        key={s}
                                        className={s === "REVOKED" ? "danger" : "secondary"}
                                        disabled={busy || i.status === "REVOKED"}
                                        onClick={() =>
                                          run(b.id, `Instrument ${shortId(i.id)} set to ${s.toLowerCase()}.`, () =>
                                            api.updatePayoutInstrumentStatus(b.id, i.id, s),
                                          )
                                        }
                                      >
                                        {s.toLowerCase()}
                                      </button>
                                    ))}
                                  </td>
                                </tr>
                                {recipients[i.id] && (
                                  <tr>
                                    <td colSpan={6}>
                                      <div className="subpanel">
                                        <h4>Provider recipients</h4>
                                        {recipients[i.id].length === 0 && (
                                          <p className="muted">
                                            No provider mapping yet — this instrument cannot be paid through any
                                            provider until one is registered.
                                          </p>
                                        )}
                                        {recipients[i.id].map((r) => (
                                          <div key={r.id} className="entry">
                                            <span>
                                              <b>{r.provider}</b>{" "}
                                              <span className="muted">{r.providerEnvironment.toLowerCase()}</span>{" "}
                                              <span className="mono muted">…{r.providerRecipientCodeSuffix}</span>
                                            </span>
                                            <StatusPill value={r.status} />
                                          </div>
                                        ))}
                                        <RegisterRecipient
                                          configs={configs}
                                          busy={busy}
                                          onSubmit={(configId, code) => registerRecipient(b.id, i.id, configId, code)}
                                        />
                                      </div>
                                    </td>
                                  </tr>
                                )}
                              </Fragment>
                            ))}
                          </tbody>
                        </table>
                      )}

                      <div className="subpanel" style={{ marginTop: 12 }}>
                        <h4>Add payout instrument</h4>
                        <p className="sub">
                          New instruments start <span className="mono">PENDING_VERIFICATION</span>. Verification is a
                          platform/provider step, not tenant self-service, so an instrument cannot be marked verified
                          from here — that separation is what stops a tenant self-approving a payout destination.
                        </p>
                        <form className="form-grid" onSubmit={(e) => createInstrument(e, b.id)}>
                          <label>
                            Type
                            <select
                              value={form.instrumentType}
                              onChange={(e) => setForm({ ...form, instrumentType: e.target.value })}
                            >
                              {INSTRUMENT_TYPES.map((t) => (
                                <option key={t} value={t}>
                                  {t.replace(/_/g, " ").toLowerCase()}
                                </option>
                              ))}
                            </select>
                          </label>
                          <label>
                            Account name
                            <input
                              value={form.accountName}
                              onChange={(e) => setForm({ ...form, accountName: e.target.value })}
                              required
                            />
                          </label>
                          <label>
                            Country
                            <input
                              value={form.country}
                              onChange={(e) => setForm({ ...form, country: e.target.value })}
                              placeholder="GB"
                              maxLength={2}
                              required
                            />
                          </label>
                          <label>
                            Currency
                            <input
                              value={form.currency}
                              onChange={(e) => setForm({ ...form, currency: e.target.value })}
                              placeholder="GBP"
                              maxLength={3}
                              required
                            />
                          </label>
                          <label>
                            Bank code {!bankAccount && <span className="muted">(bank accounts only)</span>}
                            <input
                              value={form.bankCode}
                              onChange={(e) => setForm({ ...form, bankCode: e.target.value })}
                              required={bankAccount}
                            />
                          </label>
                          <label>
                            Masked identifier
                            <input
                              value={form.maskedIdentifier}
                              onChange={(e) => setForm({ ...form, maskedIdentifier: e.target.value })}
                              placeholder="****4321"
                              required
                            />
                          </label>
                          <label>
                            External reference
                            <input
                              value={form.externalReference}
                              onChange={(e) => setForm({ ...form, externalReference: e.target.value })}
                              placeholder="vault://payouts/supplier-a"
                              pattern="(vault|secret|token|merchant-ref|instrument-ref):.+"
                              title="Must be an opaque reference: vault:, secret:, token:, merchant-ref: or instrument-ref:"
                              required
                            />
                            <span className="muted">
                              An opaque pointer, never the account number itself — one of{" "}
                              <span className="mono">vault: secret: token: merchant-ref: instrument-ref:</span>
                            </span>
                          </label>
                          <button type="submit" disabled={busy}>
                            {busy ? "Saving…" : "Add instrument"}
                          </button>
                        </form>
                      </div>
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
        {beneficiaries !== null && beneficiaries.length === 0 && (
          <EmptyState
            title="No beneficiaries"
            hint="Add one below, then give it the payout instrument that money should land in."
          />
        )}
      </section>

      <section className="panel" style={{ marginTop: 18 }}>
        <h2>Add beneficiary</h2>
        <p className="sub">
          A beneficiary names who is being paid; the instrument added against it says where the money lands. New
          beneficiaries are untrusted until risk review marks them otherwise.
        </p>
        <form
          className="form-grid"
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            setError(null);
            setNotice(null);
            try {
              const b = await api.createBeneficiary(newName.trim(), newAccountId.trim());
              setNotice(`Beneficiary ${b.name} created — add a payout instrument next.`);
              setNewName("");
              setNewAccountId("");
              setBeneficiaries(await api.listBeneficiaries());
            } catch (err) {
              setError((err as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <label>
            Name
            <input value={newName} onChange={(e) => setNewName(e.target.value)} required />
          </label>
          <label>
            Destination account id
            <input
              value={newAccountId}
              onChange={(e) => setNewAccountId(e.target.value)}
              placeholder="uuid from Accounts"
              required
            />
          </label>
          <button type="submit" disabled={busy}>
            {busy ? "Saving…" : "Add beneficiary"}
          </button>
        </form>
      </section>
    </Shell>
  );
}

function RegisterRecipient({
  configs,
  busy,
  onSubmit,
}: {
  configs: ProviderConfigView[];
  busy: boolean;
  onSubmit: (configId: string, code: string) => void;
}) {
  const [configId, setConfigId] = useState("");
  const [code, setCode] = useState("");

  if (configs.length === 0) {
    return (
      <p className="muted">
        No provider configurations available. A provider must be configured, compliance-approved and active before a
        recipient can be registered against it.
      </p>
    );
  }

  return (
    <div className="form-row" style={{ marginTop: 10 }}>
      <label>
        Provider
        <select value={configId} onChange={(e) => setConfigId(e.target.value)}>
          <option value="">Select…</option>
          {configs.map((c) => (
            <option key={c.id} value={c.id}>
              {c.provider} · {c.environment.toLowerCase()}
            </option>
          ))}
        </select>
      </label>
      <label style={{ flex: 1 }}>
        Provider recipient code
        <input
          value={code}
          onChange={(e) => setCode(e.target.value)}
          placeholder="the provider's own recipient token"
        />
      </label>
      <button
        disabled={busy || !configId || !code.trim()}
        onClick={() => {
          onSubmit(configId, code.trim());
          setCode("");
        }}
      >
        Register
      </button>
    </div>
  );
}
