"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { api, setSession, setToken } from "../lib/api";

export default function LoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<"register" | "login">("login");
  const [tenantId, setTenantId] = useState("");
  const [tenantName, setTenantName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [remember, setRemember] = useState(true);

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
          <div className="auth-brand-row">
            <div className="auth-brand-lockup">
              <span className="auth-logo-mark" aria-hidden>
                <i /><i /><i />
              </span>
              <span className="auth-brand-copy">
                <strong>TrustLedger</strong>
              </span>
            </div>
            <span className="auth-env"><i />SANDBOX</span>
          </div>

          <div className="auth-message">
            <p className="eyebrow">Governed money movement</p>
            <h1>Operate every<br />payment with<br /><span>confidence.</span></h1>
            <p>
              One calm workspace for ledger-backed transfers, fraud decisions, payment-provider controls,
              reconciliation and audit evidence.
            </p>
          </div>

          <div className="auth-proof" aria-label="Platform principles">
            <article className="proof-item">
              <span className="proof-icon proof-shield" aria-hidden />
              <strong>Ledger-first truth</strong>
              <span>Payments recorded once, consistently, and tied to the source.</span>
            </article>
            <article className="proof-item">
              <span className="proof-icon proof-search" aria-hidden />
              <strong>Explainable risk</strong>
              <span>Clear signals, policy-based decisions, and full reasoning.</span>
            </article>
            <article className="proof-item">
              <span className="proof-icon proof-document" aria-hidden />
              <strong>Defensible evidence</strong>
              <span>Complete timelines and immutable records for every payment.</span>
            </article>
          </div>
        </section>

        <section className="auth-panel">
          <div className="card authcard">
            <div className="authcard-head">
              <h2>{mode === "register" ? "Create your tenant" : "Welcome back"}</h2>
              <p className="sub">
                {mode === "register"
                  ? "Set up an isolated organisation workspace and enter the operations console."
                  : "Sign in to continue to TrustLedger"}
              </p>
            </div>

            <form className="form" onSubmit={submit}>
              {mode === "register" ? (
                <>
                  <label htmlFor="tenant-name">Organisation name</label>
                  <input id="tenant-name" autoComplete="organization" placeholder="Acme Financial" value={tenantName} onChange={(e) => setTenantName(e.target.value)} required />
                </>
              ) : (
                <>
                  <label htmlFor="tenant-id">Organisation ID</label>
                  <input id="tenant-id" autoComplete="organization" placeholder="Tenant identifier" value={tenantId} onChange={(e) => setTenantId(e.target.value)} required />
                </>
              )}
              <label htmlFor="email">{mode === "login" ? "Work email" : "Email"}</label>
              <input id="email" type="email" autoComplete="email" placeholder="you@company.com" value={email} onChange={(e) => setEmail(e.target.value)} required />
              <label htmlFor="password">Password</label>
              <input id="password" type="password" autoComplete={mode === "register" ? "new-password" : "current-password"} placeholder="Enter your password" value={password} onChange={(e) => setPassword(e.target.value)} required />
              {mode === "login" && (
                <label className="auth-remember">
                  <input type="checkbox" checked={remember} onChange={(event) => setRemember(event.target.checked)} />
                  <span>Remember me</span>
                </label>
              )}
              <button className="auth-submit" type="submit" disabled={busy}>
                {busy ? "Working…" : mode === "register" ? "Create tenant and continue" : "Sign in"}
              </button>
              {error && <p className="error" role="alert">{error}</p>}
            </form>
            <div className="auth-divider"><span>or</span></div>
            <div className="auth-alternative">
              <div>
                <strong>{mode === "login" ? "Create your tenant" : "Already have a tenant?"}</strong>
                <span>{mode === "login" ? "Set up your organisation and invite your team." : "Return to secure sign in."}</span>
              </div>
              <button type="button" className="auth-switch" onClick={() => { setError(null); setMode(mode === "login" ? "register" : "login"); }}>
                {mode === "login" ? "Create your tenant" : "Log in"}
              </button>
            </div>
          </div>
        </section>
      </div>
      <footer className="auth-footer">
        <span>Tenant-scoped access · sensitive actions are audited</span>
        <span><i /> System status <b>Operational</b> <a href="#privacy">Privacy</a> <a href="#terms">Terms</a></span>
      </footer>
    </main>
  );
}
