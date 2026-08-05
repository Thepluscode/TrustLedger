"use client";

import { useEffect, useState, type FormEvent } from "react";
import Shell from "../../components/Shell";
import { ConfirmModal, EmptyState, SkeletonRows, StatusPill } from "../../components/ui";
import { api } from "../../lib/api";
import { dateTime } from "../../lib/format";
import type { ApiKey } from "../../lib/types";

// Scopes a key may carry: every assignable role except OWNER (a leaked key must not own the tenant).
const SCOPES = ["ADMIN", "FRAUD_MANAGER", "FRAUD_ANALYST", "FINANCE_OPERATOR", "AUDITOR", "VIEWER", "DEVELOPER"];

type Pending = { kind: "rotate" | "revoke"; key: ApiKey };

export default function ApiKeysPage() {
  const [keys, setKeys] = useState<ApiKey[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [scope, setScope] = useState("DEVELOPER");
  const [secret, setSecret] = useState<{ name: string; value: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const [modalBusy, setModalBusy] = useState(false);

  function load() {
    api.listApiKeys().then(setKeys).catch((e) => setError((e as Error).message));
  }
  useEffect(load, []);

  async function create(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const k = await api.createApiKey(name.trim(), scope);
      setSecret({ name: k.name, value: k.secret });
      setCopied(false);
      setName("");
      load();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function confirmPending() {
    if (!pending) return;
    setError(null);
    setModalBusy(true);
    try {
      if (pending.kind === "rotate") {
        const k = await api.rotateApiKey(pending.key.id);
        setSecret({ name: k.name, value: k.secret });
        setCopied(false);
      } else {
        await api.revokeApiKey(pending.key.id);
      }
      setPending(null);
      load();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setModalBusy(false);
    }
  }

  return (
    <Shell active="/developer/api-keys">
      <header className="topbar">
        <div>
          <p className="eyebrow">Developer</p>
          <h1>API keys</h1>
          <p className="sub">
            Programmatic access to the REST API. A key carries a scope (an access role) and is shown once at
            creation — store it securely; only its hash is kept. Authenticate with
            {" "}<span className="mono">Authorization: ApiKey &lt;key&gt;</span>.
          </p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}

      <section className="panel api-key-create">
        <div className="panelHeader">
          <div>
            <h2>Create a key</h2>
            <p className="sub">The secret is displayed only once. Rotating mints a new secret and retires the old one.</p>
          </div>
        </div>
        <div className="panelBody">
          <form className="row" onSubmit={create}>
            <div style={{ flex: 1, minWidth: 220 }}>
              <label htmlFor="key-name" style={{ marginTop: 0 }}>Name</label>
              <input id="key-name" value={name} onChange={(e) => setName(e.target.value)} required placeholder="CI deploy bot" />
            </div>
            <div>
              <label htmlFor="key-scope" style={{ marginTop: 0 }}>Scope</label>
              <select id="key-scope" value={scope} onChange={(e) => setScope(e.target.value)} style={{ minWidth: 170 }}>
                {SCOPES.map((s) => <option key={s} value={s}>{s.replace(/_/g, " ").toLowerCase()}</option>)}
              </select>
            </div>
            <button type="submit" disabled={busy || !name.trim()}>{busy ? "Creating…" : "Create key"}</button>
          </form>
        </div>
      </section>

      <section className="panel api-key-list" style={{ marginTop: 18 }}>
        <table>
          <thead>
            <tr><th>Name</th><th>Key</th><th>Scope</th><th>Last used</th><th>Created</th><th>Status</th><th></th></tr>
          </thead>
          <tbody>
            {keys === null && <SkeletonRows cols={7} />}
            {keys?.map((k) => (
              <tr key={k.id}>
                <td>{k.name}</td>
                <td className="mono muted">tlk_{k.keyPrefix}…</td>
                <td>{k.scope.replace(/_/g, " ").toLowerCase()}</td>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{k.lastUsedAt ? dateTime(k.lastUsedAt) : "Never"}</td>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(k.createdAt)}</td>
                <td><StatusPill value={k.revoked ? "REVOKED" : "ACTIVE"} /></td>
                <td style={{ textAlign: "right", whiteSpace: "nowrap" }}>
                  {!k.revoked && (
                    <>
                      <button className="secondary" onClick={() => setPending({ kind: "rotate", key: k })}>Rotate</button>{" "}
                      <button className="danger" onClick={() => setPending({ kind: "revoke", key: k })}>Revoke</button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {keys !== null && keys.length === 0 && (
          <EmptyState title="No API keys yet" hint="Create one above to call the API outside the console." />
        )}
      </section>

      <ConfirmModal
        open={pending !== null}
        title={pending?.kind === "rotate" ? "Rotate API key" : "Revoke API key"}
        body={
          pending?.kind === "rotate"
            ? <>Rotating <b>{pending?.key.name}</b> issues a new secret and immediately retires the current one. Any caller still using the old secret will start getting 401.</>
            : <>Revoking <b>{pending?.key.name}</b> permanently disables it. This can&apos;t be undone — create a new key to restore access.</>
        }
        confirmWord={pending?.kind === "rotate" ? "ROTATE" : "REVOKE"}
        confirmLabel={pending?.kind === "rotate" ? "Rotate key" : "Revoke key"}
        danger={pending?.kind === "revoke"}
        busy={modalBusy}
        onConfirm={confirmPending}
        onCancel={() => setPending(null)}
      />
      {secret && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="API key secret shown once">
          <div className="modal api-secret-modal">
            <span className="secret-warning-icon" aria-hidden>!</span>
            <div className="secret-modal-head">
              <p>Secret shown once</p>
              <h3>Save your API key now</h3>
              <span>This secret cannot be recovered after you close this dialog. Store it in your secrets manager, never in source control.</span>
            </div>
            <div className="secret-value">
              <span>{secret.name}</span>
              <code>{secret.value}</code>
            </div>
            <div className="actions">
              <button
                className="secondary"
                onClick={async () => {
                  await navigator.clipboard.writeText(secret.value);
                  setCopied(true);
                }}
              >
                {copied ? "Copied" : "Copy secret"}
              </button>
              <button onClick={() => setSecret(null)}>I&apos;ve saved it</button>
            </div>
            <p className="secret-audit-note">Key creation and rotation are recorded in the audit log.</p>
          </div>
        </div>
      )}
    </Shell>
  );
}
