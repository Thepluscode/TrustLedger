"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import Shell from "../components/Shell";
import { EmptyState, RiskBadge, SkeletonRows, StatusPill } from "../components/ui";
import { api } from "../lib/api";
import { dateTime, money, shortId } from "../lib/format";
import type { TransferListItem } from "../lib/types";

const RISK_BANDS = ["all", "low", "medium", "high", "critical"] as const;
type RiskBand = (typeof RISK_BANDS)[number];

function bandOf(score: number): Exclude<RiskBand, "all"> {
  return score >= 85 ? "critical" : score >= 60 ? "high" : score >= 30 ? "medium" : "low";
}

export default function TransfersListPage() {
  const [transfers, setTransfers] = useState<TransferListItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState("all");
  const [channel, setChannel] = useState("all");
  const [band, setBand] = useState<RiskBand>("all");

  useEffect(() => {
    api.listTransfers().then(setTransfers).catch((e) => setError((e as Error).message));
  }, []);

  const statuses = useMemo(
    () => ["all", ...Array.from(new Set((transfers ?? []).map((t) => t.status)))],
    [transfers],
  );

  const filtered = (transfers ?? []).filter(
    (t) =>
      (status === "all" || t.status === status) &&
      (channel === "all" || t.channel === channel) &&
      (band === "all" || bandOf(t.riskScore) === band),
  );

  return (
    <Shell active="/transfers">
      <header className="topbar">
        <div>
          <p className="eyebrow">Money Movement</p>
          <h1>Transfers</h1>
          <p className="sub">Every transfer, newest first — status, risk, and rail at a glance. Open one for the full trail.</p>
        </div>
        <Link href="/transfers/new" className="btn" style={{ textDecoration: "none" }}>
          New transfer
        </Link>
      </header>
      {error && <p className="error">{error}</p>}

      <section className="panel">
        <div className="panelHeader">
          <div className="row" style={{ gap: 12 }}>
            <div>
              <label htmlFor="f-status" style={{ marginTop: 0 }}>Status</label>
              <select id="f-status" value={status} onChange={(e) => setStatus(e.target.value)} style={{ minWidth: 170 }}>
                {statuses.map((s) => <option key={s} value={s}>{s === "all" ? "All statuses" : s.replace(/_/g, " ")}</option>)}
              </select>
            </div>
            <div>
              <label htmlFor="f-channel" style={{ marginTop: 0 }}>Rail</label>
              <select id="f-channel" value={channel} onChange={(e) => setChannel(e.target.value)} style={{ minWidth: 130 }}>
                <option value="all">All rails</option>
                <option value="INTERNAL">Internal</option>
                <option value="EXTERNAL">External</option>
              </select>
            </div>
            <div>
              <label htmlFor="f-band" style={{ marginTop: 0 }}>Risk</label>
              <select id="f-band" value={band} onChange={(e) => setBand(e.target.value as RiskBand)} style={{ minWidth: 130 }}>
                {RISK_BANDS.map((b) => <option key={b} value={b}>{b === "all" ? "All risk" : b}</option>)}
              </select>
            </div>
          </div>
        </div>
        <table>
          <thead>
            <tr>
              <th>Status</th>
              <th>Transaction</th>
              <th>From → To</th>
              <th className="num">Amount</th>
              <th>Risk</th>
              <th>Rail</th>
              <th>Created</th>
            </tr>
          </thead>
          <tbody>
            {transfers === null && <SkeletonRows cols={7} rows={6} />}
            {filtered.map((t) => (
              <tr key={t.id}>
                <td><StatusPill value={t.status} /></td>
                <td><Link href={`/transfers/${t.id}`} className="mono">{shortId(t.id)}</Link></td>
                <td className="mono muted">{shortId(t.sourceAccountId)} → {shortId(t.destinationAccountId)}</td>
                <td className="num amount">{money(t.amount, t.currency)}</td>
                <td><RiskBadge score={t.riskScore} /></td>
                <td className="muted">{t.channel.toLowerCase()}</td>
                <td className="muted" style={{ whiteSpace: "nowrap" }}>{dateTime(t.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {transfers !== null && filtered.length === 0 && (
          <EmptyState
            title={transfers.length === 0 ? "No transfers yet" : "No transfers match these filters"}
            hint={transfers.length === 0 ? "Create your first transfer to see it scored and posted here." : "Clear or widen the filters above."}
          />
        )}
      </section>
    </Shell>
  );
}
