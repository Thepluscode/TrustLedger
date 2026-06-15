"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import Shell from "../components/Shell";
import { api } from "../lib/api";

type Step = { title: string; detail: string; done: boolean; href: string; cta: string; core: boolean };

const DEFAULT_POLICY = { monitor: 25, mfa: 45, hold: 65, reject: 85, deviceTrustAfter: 3, autoFreezeEnabled: false };

export default function OnboardingPage() {
  const [steps, setSteps] = useState<Step[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      api.listAccounts().catch(() => []),
      api.listTransfers().catch(() => []),
      api.getFraudPolicy().catch(() => null),
      api.listEvidence().catch(() => []),
      api.listProviderConfigs().catch(() => []),
    ])
      .then(([accounts, transfers, policy, evidence, providers]) => {
        const policyCustomised =
          !!policy &&
          (["monitor", "mfa", "hold", "reject", "deviceTrustAfter"] as const).some(
            (k) => policy[k] !== DEFAULT_POLICY[k],
          ) || (policy?.autoFreezeEnabled ?? false) !== DEFAULT_POLICY.autoFreezeEnabled;
        setSteps([
          { title: "Organisation created", detail: "Your tenant is live and isolated.", done: true, href: "/dashboard", cta: "Open dashboard", core: true },
          { title: "Open a funded account", detail: "You need at least one account to move money.", done: accounts.length > 0, href: "/accounts", cta: "Open an account", core: true },
          { title: "Make your first transfer", detail: "Watch it get fraud-scored, then posted to the ledger.", done: transfers.length > 0, href: "/transfers/new", cta: "Create transfer", core: true },
          { title: "Tune your fraud policy", detail: policyCustomised ? "Custom thresholds applied." : "Using safe defaults — review and adjust for your risk appetite.", done: policyCustomised, href: "/admin", cta: "Review policy", core: false },
          { title: "Connect a payment provider", detail: "Optional — the sandbox rail works without one.", done: providers.length > 0, href: "/admin", cta: "Add provider", core: false },
          { title: "Export an evidence pack", detail: "Generate a checksummed pack from a fraud case.", done: evidence.length > 0, href: "/fraud-cases", cta: "Open cases", core: false },
        ]);
      })
      .catch((e) => setError((e as Error).message));
  }, []);

  const coreDone = (steps ?? []).filter((s) => s.core && s.done).length;
  const coreTotal = (steps ?? []).filter((s) => s.core).length;
  const allDone = steps !== null && steps.every((s) => s.done);

  return (
    <Shell active="/onboarding">
      <header className="topbar">
        <div>
          <p className="eyebrow">Getting started</p>
          <h1>Onboarding</h1>
          <p className="sub">A few steps to a working pilot. Each item checks itself off from your real data.</p>
        </div>
      </header>
      {error && <p className="error">{error}</p>}

      {steps && (
        <div className={`notice${allDone ? "" : " warn"}`} style={{ marginBottom: 4 }}>
          {allDone
            ? "All set — your tenant is fully configured."
            : `Core setup: ${coreDone} of ${coreTotal} complete.`}
        </div>
      )}

      <section className="panel">
        {steps === null ? (
          <div className="panelBody"><div className="skeleton" style={{ minHeight: 60 }} /></div>
        ) : (
          steps.map((s, i) => (
            <div key={s.title} className="row" style={{ justifyContent: "space-between", gap: 16, padding: "16px 18px", borderTop: i ? "1px solid var(--line)" : undefined, alignItems: "center" }}>
              <div className="row" style={{ gap: 14, alignItems: "center" }}>
                <span className={`risk ${s.done ? "low" : "medium"}`} style={{ minWidth: 34, justifyContent: "center" }}>{s.done ? "✓" : i + 1}</span>
                <div>
                  <div style={{ fontWeight: 600 }}>{s.title}{!s.core && <span className="muted" style={{ fontWeight: 400 }}> · optional</span>}</div>
                  <div className="muted" style={{ fontSize: 13 }}>{s.detail}</div>
                </div>
              </div>
              <Link href={s.href} className={`btn${s.done ? " secondary" : ""}`} style={{ textDecoration: "none", whiteSpace: "nowrap" }}>
                {s.done ? "Review" : s.cta}
              </Link>
            </div>
          ))
        )}
      </section>
    </Shell>
  );
}
