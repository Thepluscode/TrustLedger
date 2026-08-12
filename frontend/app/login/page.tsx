"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { api, setSession, setToken } from "../lib/api";

export default function LoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<"register" | "login">("register");
  const [tenantId, setTenantId] = useState("");
  const [tenantName, setTenantName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const res = mode === "register"
        ? await api.register(tenantName, email, password)
        : await api.login(tenantId, email, password);
      setToken(res.token);
      setSession({ email: res.email, role: res.role, tenantId: res.tenantId });
      router.replace("/dashboard");
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth">
      <div className="auth-layout">
        <section className="auth-story" aria-label="TrustLedger product introduction">
          <div className="auth-brand-lockup">
            <span className="brand-mark" aria-hidden style={{ display: "grid", color: "#07140f", fontSize: "inherit" }}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                <path d="M6 4h12v16H6z" />
                <path d="M9 8h6M9 12h6M9 16h3" />
                <path d="m14.5 15.5 1.5 1.5 3-3" />
              </svg>
            </span>
            <span className="auth-brand-copy" style={{ display: "grid", gap: 1, color: "inherit", fontSize: "inherit" }}>
              <strong style={{ color: "var(--text)", fontSize: 18 }}>TrustLedger</strong>
              <span style={{ color: "var(--muted)", fontSize: 11 }}>Financial control plane</span>
            </span>
          </div>

          <div className="auth-message">
            <p className="eyebrow">Governed money movement</p>
            <h1>Operate every payment with confidence.</h1>
            <p>
              One calm workspace for ledger-backed transfers, fraud decisions, payment-provider controls,
              reconciliation and audit evidence.
            </p>
          </div>

          <div className="auth-proof" aria-label="Platform principles">
            <article className="proof-item">
              <strong>Ledger first</strong>
              <span>Balanced, immutable financial records</span>
            </article>
            <article className="proof-item">
              <strong>Fail closed</strong>
              <span>Unsafe payment paths remain blocked</span>
            </article>
            <article className="proof-item">
              <strong>Evidence built in</strong>
              <span>Every sensitive action is attributable</span>
            </article>
          </div>
        </section>

        <section className="auth-panel">
          <div className="card authcard">
            <div className="authcard-head">
              <p className="eyebrow">Secure workspace</p>
              <h2>{mode === "register" ? "Create your tenant" : "Welcome back"}</h2>
              <p className="sub">
                {mode === "register"
                  ? "Set up an isolated organisation workspace and enter the operations console."
                  : "Sign in to your organisation's financial operations workspace."}
              </p>
            </div>

            <div className="tabs" role="tablist" aria-label="Authentication mode">
              <button type="button" role="tab" aria-selected={mode === "register"} className={mode === "register" ? "active" : ""} onClick={() => setMode("register")}>
                Create tenant
              </button>
              <button type="button" role="tab" aria-selected={mode === "login"} className={mode === "login" ? "active" : ""} onClick={() => setMode("login")}>
                Log in
              </button>
            </div>

            <form className="form" onSubmit={submit}>
              {mode === "register" ? (
                <>
                  <label htmlFor="tenant-name">Organisation name</label>
                  <input id="tenant-name" autoComplete="organization" placeholder="Acme Financial" value={tenantName} onChange={(e) => setTenantName(e.target.value)} required />
                </>
              ) : (
                <>
                  <label htmlFor="tenant-id">Tenant ID</label>
                  <input id="tenant-id" autoComplete="organization" placeholder="Tenant identifier" value={tenantId} onChange={(e) => setTenantId(e.target.value)} required />
                </>
              )}
              <label htmlFor="email">Email</label>
              <input id="email" type="email" autoComplete="email" placeholder="you@company.com" value={email} onChange={(e) => setEmail(e.target.value)} required />
              <label htmlFor="password">Password</label>
              <input id="password" type="password" autoComplete={mode === "register" ? "new-password" : "current-password"} placeholder="Enter your password" value={password} onChange={(e) => setPassword(e.target.value)} required />
              <button className="auth-submit" type="submit" disabled={busy}>
                {busy ? "Working…" : mode === "register" ? "Create tenant and continue" : "Enter workspace"}
              </button>
              {error && <p className="error" role="alert">{error}</p>}
            </form>
            <p className="auth-footnote">Access is tenant-scoped and all sensitive actions are audited.</p>
          </div>
        </section>
      </div>
    </main>
  );
}
