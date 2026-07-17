"use client";

import { useEffect, useState } from "react";
import { api } from "../lib/api";
import { StatusPill } from "./ui";

export default function PlatformProductionGate() {
  const [state, setState] = useState<{
    productionExecutionEnabled: boolean;
    activeCanaryRequired: boolean;
    policy: string;
  } | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.getProductionReadiness()
      .then(setState)
      .catch((failure) => setError((failure as Error).message));
  }, []);

  if (error) {
    return <p className="error">Platform production-state check failed: {error}</p>;
  }
  if (!state) {
    return <div className="skeleton" style={{ minHeight: 42, marginBottom: 18 }} />;
  }

  return (
    <div className="notice" style={{ marginBottom: 18 }}>
      <div className="row" style={{ alignItems: "center", gap: 10, flexWrap: "wrap" }}>
        <b>Platform production execution</b>
        <StatusPill value={state.productionExecutionEnabled ? "ENABLED" : "DISABLED"} />
        {state.activeCanaryRequired && <span className="badge">Active canary required</span>}
      </div>
      <p className="sub" style={{ marginTop: 8 }}>
        {state.productionExecutionEnabled
          ? "The global switch is enabled. Every payout still requires an eligible provider configuration and an active canary with available exposure."
          : "The global switch is disabled. Production payouts fail closed even when a canary is approved."}
      </p>
      <p className="hint">Policy: {state.policy.replace(/_/g, " ").toLowerCase()}</p>
    </div>
  );
}
