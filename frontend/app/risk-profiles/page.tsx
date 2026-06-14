"use client";

import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { EmptyState, RiskBadge, SkeletonRows, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import { dateTime, shortId } from "../lib/format";
import type { BeneficiaryProfile, DeviceProfile, UserProfile } from "../lib/types";

const num = (x: string | number) => {
  const n = typeof x === "string" ? parseFloat(x) : x;
  return Number.isNaN(n) ? String(x) : n.toLocaleString("en-GB", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
};

export default function RiskProfilesPage() {
  const [devices, setDevices] = useState<DeviceProfile[] | null>(null);
  const [beneficiaries, setBeneficiaries] = useState<BeneficiaryProfile[] | null>(null);
  const [users, setUsers] = useState<UserProfile[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.deviceProfiles().then(setDevices).catch((e) => setError((e as Error).message));
    api.beneficiaryProfiles().then(setBeneficiaries).catch((e) => setError((e as Error).message));
    api.userProfiles().then(setUsers).catch((e) => setError((e as Error).message));
  }, []);

  return (
    <Shell active="/risk-profiles">
      <header className="topbar">
        <div>
          <p className="eyebrow">Fraud Intelligence</p>
          <h1>Risk profiles</h1>
          <p className="sub">The behavioural baselines the gate maintains — devices, recipients, and user spend.</p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}

      <section className="panel">
        <div className="panelHeader"><div><h2>Devices</h2><p className="sub">A device is auto-trusted after enough clean transfers; trusted devices stop adding risk.</p></div></div>
        <table>
          <thead>
            <tr><th>Device</th><th>User</th><th>Trusted</th><th className="num">Transfers</th><th>Risk</th><th>Country</th><th>Last seen</th></tr>
          </thead>
          <tbody>
            {devices === null && <SkeletonRows cols={7} />}
            {devices?.map((d) => (
              <tr key={d.id}>
                <td className="mono">{d.deviceId}</td>
                <td className="mono muted">{shortId(d.userId)}</td>
                <td><StatusPill value={d.trusted ? "ACTIVE" : "PENDING"} /></td>
                <td className="num">{d.transferCount}</td>
                <td><RiskBadge score={d.riskScore} /></td>
                <td className="muted">{d.country ?? "—"}</td>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(d.lastSeenAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {devices !== null && devices.length === 0 && (
          <EmptyState title="No device profiles yet" hint="Devices appear here as transfers are scored and posted." />
        )}
      </section>

      <section className="panel" style={{ marginTop: 18 }}>
        <div className="panelHeader"><div><h2>Beneficiaries</h2><p className="sub">Recipient risk — volume, distinct senders (mule signal at 5+), and fraud linkage.</p></div></div>
        <table>
          <thead>
            <tr><th>Account</th><th className="num">Transfers</th><th className="num">Senders</th><th className="num">Received</th><th>Risk</th><th>Flags</th><th>First seen</th></tr>
          </thead>
          <tbody>
            {beneficiaries === null && <SkeletonRows cols={7} />}
            {beneficiaries?.map((b) => (
              <tr key={b.id}>
                <td className="mono">{shortId(b.beneficiaryAccountId)}</td>
                <td className="num">{b.totalTransfers}</td>
                <td className="num">{b.distinctSenders}</td>
                <td className="num amount">{num(b.totalAmountReceived)}</td>
                <td><RiskBadge score={b.riskScore} /></td>
                <td>
                  <div className="row" style={{ gap: 6 }}>
                    {b.confirmedFraudLinked && <span className="pill bad">fraud-linked</span>}
                    {b.distinctSenders >= 5 && <span className="pill warn">mule pattern</span>}
                    {!b.confirmedFraudLinked && b.distinctSenders < 5 && <span className="muted">—</span>}
                  </div>
                </td>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(b.firstTransferAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {beneficiaries !== null && beneficiaries.length === 0 && (
          <EmptyState title="No beneficiary profiles yet" hint="Recipients appear here once transfers target them." />
        )}
      </section>

      <section className="panel" style={{ marginTop: 18 }}>
        <div className="panelHeader"><div><h2>Users</h2><p className="sub">Per-user spend baseline used to flag amount anomalies.</p></div></div>
        <table>
          <thead>
            <tr><th>User</th><th className="num">Median</th><th className="num">Max normal</th><th className="num">Transfers</th><th>Risk level</th></tr>
          </thead>
          <tbody>
            {users === null && <SkeletonRows cols={5} />}
            {users?.map((u) => (
              <tr key={u.userId}>
                <td className="mono">{shortId(u.userId)}</td>
                <td className="num amount">{num(u.medianTransferAmount)}</td>
                <td className="num amount">{num(u.maxNormalTransferAmount)}</td>
                <td className="num">{u.transferCount}</td>
                <td><span className="badge">{u.riskLevel?.toLowerCase() ?? "—"}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
        {users !== null && users.length === 0 && (
          <EmptyState title="No user profiles yet" hint="A user's baseline forms after their first completed transfer." />
        )}
      </section>
    </Shell>
  );
}
