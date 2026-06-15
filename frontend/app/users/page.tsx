"use client";

import { useEffect, useState, type FormEvent } from "react";
import Shell from "../components/Shell";
import { EmptyState, SkeletonRows } from "../components/ui";
import { api } from "../lib/api";
import { dateTime } from "../lib/format";
import type { TeamMember } from "../lib/types";

const ROLES = ["OWNER", "ADMIN", "FRAUD_MANAGER", "FRAUD_ANALYST", "FINANCE_OPERATOR", "AUDITOR", "VIEWER", "DEVELOPER"];

export default function UsersPage() {
  const [members, setMembers] = useState<TeamMember[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteRole, setInviteRole] = useState("FRAUD_ANALYST");
  const [tempPassword, setTempPassword] = useState<{ email: string; password: string } | null>(null);
  const [busy, setBusy] = useState(false);

  function load() {
    api.listUsers().then(setMembers).catch((e) => setError((e as Error).message));
  }
  useEffect(load, []);

  async function invite(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setNote(null);
    setBusy(true);
    try {
      const r = await api.inviteUser(inviteEmail.trim(), inviteRole);
      setTempPassword({ email: r.email, password: r.temporaryPassword });
      setInviteEmail("");
      load();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function changeRole(id: string, role: string) {
    setError(null);
    setNote(null);
    try {
      const r = await api.changeUserRole(id, role);
      setNote(`Role updated to ${r.role}.`);
      load();
    } catch (err) {
      setError((err as Error).message);
      load(); // re-sync the select to the actual (unchanged) role
    }
  }

  return (
    <Shell active="/users">
      <header className="topbar">
        <div>
          <p className="eyebrow">Organisation</p>
          <h1>Users &amp; roles</h1>
          <p className="sub">Invite teammates and set their access. Only an OWNER can grant OWNER; the last OWNER can&apos;t be demoted.</p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}
      {note && <p className="ok">{note}</p>}

      <section className="panel">
        <div className="panelHeader">
          <div>
            <h2>Invite a teammate</h2>
            <p className="sub">No invite emails yet — share the one-time password out of band.</p>
          </div>
        </div>
        <div className="panelBody">
          <form className="row" onSubmit={invite}>
            <div style={{ flex: 1, minWidth: 220 }}>
              <label htmlFor="inv-email" style={{ marginTop: 0 }}>Email</label>
              <input id="inv-email" type="email" value={inviteEmail} onChange={(e) => setInviteEmail(e.target.value)} required placeholder="teammate@company.com" />
            </div>
            <div>
              <label htmlFor="inv-role" style={{ marginTop: 0 }}>Role</label>
              <select id="inv-role" value={inviteRole} onChange={(e) => setInviteRole(e.target.value)} style={{ minWidth: 170 }}>
                {ROLES.map((r) => <option key={r} value={r}>{r.replace(/_/g, " ").toLowerCase()}</option>)}
              </select>
            </div>
            <button type="submit" disabled={busy || !inviteEmail.trim()}>{busy ? "Inviting…" : "Invite"}</button>
          </form>
          {tempPassword && (
            <div className="notice warn" style={{ marginTop: 12 }}>
              <b>{tempPassword.email}</b> created. One-time password (shown once): <span className="mono">{tempPassword.password}</span> — share it securely; they should sign in and it should be rotated.
            </div>
          )}
        </div>
      </section>

      <section className="panel" style={{ marginTop: 18 }}>
        <table>
          <thead>
            <tr><th>Email</th><th>Role</th><th>Added</th></tr>
          </thead>
          <tbody>
            {members === null && <SkeletonRows cols={3} />}
            {members?.map((m) => (
              <tr key={m.id}>
                <td>{m.email}</td>
                <td>
                  <select value={m.role} onChange={(e) => changeRole(m.id, e.target.value)} style={{ minWidth: 170 }} aria-label={`Role for ${m.email}`}>
                    {ROLES.map((r) => <option key={r} value={r}>{r.replace(/_/g, " ").toLowerCase()}</option>)}
                  </select>
                </td>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(m.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {members !== null && members.length === 0 && (
          <EmptyState title="No teammates yet" hint="Invite your first teammate above." />
        )}
      </section>
    </Shell>
  );
}
