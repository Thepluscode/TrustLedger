"use client";

import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { EmptyState, RiskBadge, SkeletonRows, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import { dateTime, decimal, shortId } from "../lib/format";
import type { BeneficiaryProfile, DeviceProfile, UserProfile } from "../lib/types";

export default function RiskProfilesPage() {
  const [devices, setDevices] = useState<DeviceProfile[] | null>(null);
  const [beneficiaries, setBeneficiaries] = useState<BeneficiaryProfile[] | null>(null);
  const [users, setUsers] = useState<UserProfile[] | null>(null);
  const [activeProfile, setActiveProfile] = useState<"devices" | "beneficiaries" | "users">("devices");
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

      <section className="operations-strip" aria-label="Risk profile coverage">
        <div><span>Devices</span><strong>{devices?.length ?? "—"}</strong></div>
        <div><span>Trusted devices</span><strong>{devices ? devices.filter((device) => device.trusted).length : "—"}</strong></div>
        <div><span>Beneficiaries</span><strong>{beneficiaries?.length ?? "—"}</strong></div>
        <div><span>Users</span><strong>{users?.length ?? "—"}</strong></div>
      </section>

      <nav className="record-tabs" aria-label="Risk profile type">
        {(["devices", "beneficiaries", "users"] as const).map((profile) => (
          <button
            key={profile}
            className={activeProfile === profile ? "active" : ""}
            aria-pressed={activeProfile === profile}
            onClick={() => setActiveProfile(profile)}
          >
            {profile}
          </button>
        ))}
      </nav>

      {activeProfile === "devices" && (
        <section className="panel profile-register">
          <div className="panelHeader"><div><h2>Device register</h2><p className="sub">Trust is earned from clean transfer history. An untrusted device remains a visible risk input.</p></div></div>
          <table className="desktop-table">
            <thead><tr><th>Device</th><th>User</th><th>Trust state</th><th className="num">Transfers</th><th>Risk</th><th>Country</th><th>Last seen</th></tr></thead>
            <tbody>
              {devices === null && <SkeletonRows cols={7} />}
              {devices?.map((device) => (
                <tr key={device.id}>
                  <td className="mono">{device.deviceId}</td>
                  <td className="mono muted">{shortId(device.userId)}</td>
                  <td><StatusPill value={device.trusted ? "ACTIVE" : "PENDING"} /></td>
                  <td className="num">{device.transferCount}</td>
                  <td><RiskBadge score={device.riskScore} /></td>
                  <td className="muted">{device.country ?? "—"}</td>
                  <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(device.lastSeenAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="mobile-record-list">
            {devices?.map((device) => (
              <article className="mobile-record risk-profile-record" key={device.id}>
                <div className="record-head"><div><small>Device</small><strong className="mono">{device.deviceId}</strong></div><RiskBadge score={device.riskScore} /></div>
                <div className="record-stats"><span><small>Transfers</small><b>{device.transferCount}</b></span><span><small>Trust</small><b>{device.trusted ? "Trusted" : "Observed"}</b></span><span><small>Country</small><b>{device.country ?? "—"}</b></span></div>
                <small>User {shortId(device.userId)} · last seen {dateTime(device.lastSeenAt)}</small>
              </article>
            ))}
          </div>
          {devices !== null && devices.length === 0 && <EmptyState title="No device profiles yet" hint="Devices appear here as transfers are scored and posted." />}
        </section>
      )}

      {activeProfile === "beneficiaries" && (
        <section className="panel profile-register">
          <div className="panelHeader"><div><h2>Beneficiary register</h2><p className="sub">Sender concentration and confirmed fraud linkage remain explicit; they are not hidden inside a composite score.</p></div></div>
          <table className="desktop-table">
            <thead><tr><th>Account</th><th className="num">Transfers</th><th className="num">Senders</th><th className="num">Received</th><th>Risk</th><th>Observed flags</th><th>First seen</th></tr></thead>
            <tbody>
              {beneficiaries === null && <SkeletonRows cols={7} />}
              {beneficiaries?.map((beneficiary) => (
                <tr key={beneficiary.id}>
                  <td className="mono">{shortId(beneficiary.beneficiaryAccountId)}</td>
                  <td className="num">{beneficiary.totalTransfers}</td>
                  <td className="num">{beneficiary.distinctSenders}</td>
                  <td className="num amount">{decimal(beneficiary.totalAmountReceived)}</td>
                  <td><RiskBadge score={beneficiary.riskScore} /></td>
                  <td>{beneficiary.confirmedFraudLinked ? "Fraud-linked" : beneficiary.distinctSenders >= 5 ? "Mule pattern" : <span className="muted">None recorded</span>}</td>
                  <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(beneficiary.firstTransferAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="mobile-record-list">
            {beneficiaries?.map((beneficiary) => (
              <article className="mobile-record risk-profile-record" key={beneficiary.id}>
                <div className="record-head"><div><small>Beneficiary</small><strong className="mono">{shortId(beneficiary.beneficiaryAccountId)}</strong></div><RiskBadge score={beneficiary.riskScore} /></div>
                <div className="record-stats"><span><small>Transfers</small><b>{beneficiary.totalTransfers}</b></span><span><small>Senders</small><b>{beneficiary.distinctSenders}</b></span><span><small>Received</small><b>{decimal(beneficiary.totalAmountReceived)}</b></span></div>
                <small>{beneficiary.confirmedFraudLinked ? "Confirmed fraud linkage" : beneficiary.distinctSenders >= 5 ? "Mule-pattern threshold reached" : "No adverse flags recorded"}</small>
              </article>
            ))}
          </div>
          {beneficiaries !== null && beneficiaries.length === 0 && <EmptyState title="No beneficiary profiles yet" hint="Recipients appear here once transfers target them." />}
        </section>
      )}

      {activeProfile === "users" && (
        <section className="panel profile-register">
          <div className="panelHeader"><div><h2>User baselines</h2><p className="sub">Observed transfer ranges support amount-anomaly rules; they never authorise a transfer by themselves.</p></div></div>
          <table className="desktop-table">
            <thead><tr><th>User</th><th className="num">Median</th><th className="num">Maximum normal</th><th className="num">Transfers</th><th>Risk level</th></tr></thead>
            <tbody>
              {users === null && <SkeletonRows cols={5} />}
              {users?.map((user) => (
                <tr key={user.userId}><td className="mono">{shortId(user.userId)}</td><td className="num amount">{decimal(user.medianTransferAmount)}</td><td className="num amount">{decimal(user.maxNormalTransferAmount)}</td><td className="num">{user.transferCount}</td><td>{user.riskLevel?.toLowerCase() ?? "—"}</td></tr>
              ))}
            </tbody>
          </table>
          <div className="mobile-record-list">
            {users?.map((user) => (
              <article className="mobile-record risk-profile-record" key={user.userId}>
                <div className="record-head"><div><small>User</small><strong className="mono">{shortId(user.userId)}</strong></div><span className="risk-label">{user.riskLevel?.toLowerCase() ?? "—"}</span></div>
                <div className="record-stats"><span><small>Median</small><b>{decimal(user.medianTransferAmount)}</b></span><span><small>Max normal</small><b>{decimal(user.maxNormalTransferAmount)}</b></span><span><small>Transfers</small><b>{user.transferCount}</b></span></div>
              </article>
            ))}
          </div>
          {users !== null && users.length === 0 && <EmptyState title="No user profiles yet" hint="A user's baseline forms after their first completed transfer." />}
        </section>
      )}
    </Shell>
  );
}
