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
      const res =
        mode === "register"
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
    <div className="auth">
      <div className="card authcard">
        <div className="brand">TrustLedger</div>
        <p className="muted">Ledger-first transaction & fraud operations</p>
        <div className="tabs">
          <button className={mode === "register" ? "active" : ""} onClick={() => setMode("register")}>
            Create tenant
          </button>
          <button className={mode === "login" ? "active" : ""} onClick={() => setMode("login")}>
            Log in
          </button>
        </div>
        <form className="form" onSubmit={submit}>
          {mode === "register" ? (
            <>
              <label>Organisation name</label>
              <input value={tenantName} onChange={(e) => setTenantName(e.target.value)} required />
            </>
          ) : (
            <>
              <label>Tenant ID</label>
              <input value={tenantId} onChange={(e) => setTenantId(e.target.value)} required />
            </>
          )}
          <label>Email</label>
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          <label>Password</label>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          <div style={{ marginTop: 18 }}>
            <button type="submit" disabled={busy}>
              {busy ? "..." : mode === "register" ? "Create & enter" : "Log in"}
            </button>
          </div>
          {error && <p className="error">{error}</p>}
        </form>
      </div>
    </div>
  );
}
