"use client";

import { useEffect, useMemo, useState } from "react";

type SourceState = {
  source: string;
  state: string;
  detail: string;
  tone: "ok" | "warning" | "danger" | "unknown";
};

type IncidentStep = {
  time: string;
  label: string;
  detail: string;
  source: string;
  tone: "ok" | "warning" | "danger" | "unknown";
};

type Scenario = {
  id: string;
  number: string;
  title: string;
  shortTitle: string;
  question: string;
  amount: string;
  exposure: string;
  classification: string;
  severity: "HIGH" | "MEDIUM" | "CRITICAL";
  control: string;
  conclusion: string;
  explanation: string;
  sources: SourceState[];
  steps: IncidentStep[];
  proof: string;
};

const SCENARIOS: Scenario[] = [
  {
    id: "missing-settlement",
    number: "01",
    title: "Missing settlement",
    shortTitle: "Missing settlement",
    question: "Provider says paid. The expected settlement never lands.",
    amount: "£32,400.00",
    exposure: "£32,400.00 pending confirmation",
    classification: "SETTLEMENT_MISSING",
    severity: "HIGH",
    control: "Settlement reconciliation",
    conclusion: "The payment completed at the provider, but no matching settlement line exists in the covered statement period.",
    explanation: "TrustLedger keeps the break open. It does not turn a missing record into a zero-value settlement or infer that funds failed.",
    sources: [
      { source: "Internal ledger", state: "POSTED", detail: "Transfer tl_8731 posted", tone: "ok" },
      { source: "Provider A", state: "SETTLED", detail: "Reference pa_884100", tone: "ok" },
      { source: "Webhook inbox", state: "ACCEPTED", detail: "Signature valid; processed once", tone: "ok" },
      { source: "Settlement file", state: "NO MATCH", detail: "No line in statement ST-0821", tone: "danger" },
    ],
    steps: [
      { time: "09:02:11", label: "Payment accepted", detail: "Provider reference pa_884100 recorded against the internal transfer.", source: "Provider A", tone: "ok" },
      { time: "09:02:12", label: "Canonical state advanced", detail: "Verified provider event advances the transfer once; the ledger remains balanced.", source: "TrustLedger", tone: "ok" },
      { time: "17:40:00", label: "Statement ingested", detail: "Statement ST-0821 covers the expected settlement window.", source: "Settlement", tone: "ok" },
      { time: "17:40:01", label: "Expected reference absent", detail: "No settlement line can be matched to pa_884100.", source: "Reconciliation", tone: "danger" },
      { time: "17:40:01", label: "Exception opened", detail: "The mismatch stays visible for an accountable operator conclusion.", source: "TrustLedger", tone: "warning" },
    ],
    proof: "Settlement reconciliation integration coverage; unresolved differences remain open by invariant.",
  },
  {
    id: "duplicate-event",
    number: "02",
    title: "Duplicate provider event",
    shortTitle: "Duplicate event",
    question: "A provider retries the same callback. Does money post twice?",
    amount: "£8,250.00",
    exposure: "£0 duplicated",
    classification: "DUPLICATE_SUPPRESSED",
    severity: "MEDIUM",
    control: "Durable webhook idempotency",
    conclusion: "Two deliveries carry the same provider event identity; only the first can advance financial state.",
    explanation: "The second delivery remains observable as a duplicate. It cannot create a second state transition or ledger posting.",
    sources: [
      { source: "Internal ledger", state: "1 POSTING", detail: "Debit £8,250 = credit £8,250", tone: "ok" },
      { source: "Provider A", state: "SETTLED", detail: "Reference pa_401772", tone: "ok" },
      { source: "Webhook inbox", state: "2 DELIVERIES", detail: "One accepted; one duplicate", tone: "warning" },
      { source: "Settlement file", state: "MATCHED", detail: "One financial line", tone: "ok" },
    ],
    steps: [
      { time: "10:16:02", label: "First callback accepted", detail: "Signature and provider event identity verified.", source: "Webhook", tone: "ok" },
      { time: "10:16:02", label: "Ledger posted once", detail: "Balanced debit and credit entries committed atomically.", source: "Ledger", tone: "ok" },
      { time: "10:16:09", label: "Callback redelivered", detail: "The provider retries the same event id after seven seconds.", source: "Webhook", tone: "warning" },
      { time: "10:16:09", label: "Duplicate suppressed", detail: "Existing inbox identity prevents a second transition and posting.", source: "TrustLedger", tone: "ok" },
    ],
    proof: "Duplicate webhook integration tests assert no duplicate state transition or financial posting.",
  },
  {
    id: "state-conflict",
    number: "03",
    title: "Webhook / provider disagreement",
    shortTitle: "State conflict",
    question: "The callback and provider lookup disagree. Which state is safe?",
    amount: "£14,980.00",
    exposure: "£14,980.00 unresolved",
    classification: "EXTERNAL_STATUS_MISMATCH",
    severity: "HIGH",
    control: "Deterministic state policy",
    conclusion: "The available sources do not prove a final outcome. Canonical state remains PENDING_UNKNOWN and the disagreement is escalated.",
    explanation: "TrustLedger preserves both raw states. Ambiguity is represented explicitly instead of fabricating success or failure.",
    sources: [
      { source: "Internal ledger", state: "RESERVED", detail: "Funds held; no final posting", tone: "unknown" },
      { source: "Provider A", state: "PROCESSING", detail: "Pull verification at 11:48", tone: "unknown" },
      { source: "Webhook inbox", state: "FAILED", detail: "Signed event reports failure", tone: "danger" },
      { source: "Settlement file", state: "NOT DUE", detail: "No settlement evidence yet", tone: "unknown" },
    ],
    steps: [
      { time: "11:47:21", label: "Submission acknowledged", detail: "Provider accepts the request but does not return a final state.", source: "Provider A", tone: "unknown" },
      { time: "11:47:22", label: "Failure callback received", detail: "A signed callback reports a failed state.", source: "Webhook", tone: "danger" },
      { time: "11:48:04", label: "Provider verification disagrees", detail: "Direct provider lookup still reports processing.", source: "Provider A", tone: "warning" },
      { time: "11:48:04", label: "Ambiguity retained", detail: "Canonical state remains PENDING_UNKNOWN; reservation is not released as if failure were proven.", source: "TrustLedger", tone: "unknown" },
      { time: "11:48:05", label: "Exception opened", detail: "Operator receives both source records and correlation identifiers.", source: "Reconciliation", tone: "warning" },
    ],
    proof: "External payment and reconciliation tests cover PENDING_UNKNOWN, late settlement and provider mismatch.",
  },
  {
    id: "fee-overcharge",
    number: "04",
    title: "Settlement fee overcharge",
    shortTitle: "Fee overcharge",
    question: "A £50,000 payment settles, but the received fee exceeds the contract.",
    amount: "£50,000.00",
    exposure: "£100.00 probable fee leakage",
    classification: "SETTLEMENT_FEE_MISMATCH",
    severity: "HIGH",
    control: "Temporal fee-schedule comparison",
    conclusion: "Provider A charged £425.25. The fee schedule in force for this statement period calculates £325.25. Delta: +£100.00.",
    explanation: "The expected fee is calculated against the historical contract period, not today’s schedule. The issue remains open until an operator records a supported outcome.",
    sources: [
      { source: "Internal ledger", state: "POSTED", detail: "Gross amount £50,000.00", tone: "ok" },
      { source: "Provider A", state: "SETTLED", detail: "Reference pa_500001", tone: "ok" },
      { source: "Webhook inbox", state: "INCOMPLETE", detail: "Acceptance present; settlement event absent", tone: "warning" },
      { source: "Settlement file", state: "FEE BREAK", detail: "Received fee £425.25", tone: "danger" },
    ],
    steps: [
      { time: "08:41:06", label: "Payment accepted", detail: "Provider reference pa_500001 is linked to transfer tl_500001.", source: "Provider A", tone: "ok" },
      { time: "08:41:07", label: "Internal posting completed", detail: "The £50,000 gross movement posts as balanced debit and credit entries.", source: "Ledger", tone: "ok" },
      { time: "08:41:08", label: "Webhook trail incomplete", detail: "The accepted event is present; no settlement callback arrives.", source: "Webhook", tone: "warning" },
      { time: "16:03:44", label: "Statement ST-5001 ingested", detail: "Provider reports settled with a £425.25 fee and £49,574.75 net.", source: "Settlement", tone: "ok" },
      { time: "16:03:44", label: "Historical schedule applied", detail: "0.65% + £0.25 calculates an expected fee of £325.25.", source: "Reconciliation", tone: "ok" },
      { time: "16:03:45", label: "Fee exception opened", detail: "A HIGH issue records expected, received, direction and £100.00 delta.", source: "TrustLedger", tone: "danger" },
      { time: "16:05:12", label: "Evidence pack prepared", detail: "Exact bytes can be signed with Ed25519 and independently verified using the public key.", source: "Evidence", tone: "ok" },
    ],
    proof: "34 settlement tests cover fee arithmetic and statement matching; temporal lookup and plausibility controls are mutation-verified.",
  },
  {
    id: "audit-tamper",
    number: "05",
    title: "Tampered audit record",
    shortTitle: "Audit tamper",
    question: "A privileged database actor edits the incident history after resolution.",
    amount: "£21,700.00",
    exposure: "Integrity breach detected",
    classification: "VERIFICATION_FAILED",
    severity: "CRITICAL",
    control: "Sealed checkpoint chain",
    conclusion: "The stored audit row no longer matches the sealed checkpoint digest for its window. Verification fails at the original checkpoint.",
    explanation: "Re-sealing later records cannot launder the earlier break. The development control detects edits, deletes and back-dating in tested attack scenarios.",
    sources: [
      { source: "Audit register", state: "ROW MODIFIED", detail: "Resolution reason changed", tone: "danger" },
      { source: "Checkpoint 018", state: "SEALED", detail: "Original digest retained", tone: "ok" },
      { source: "Checkpoint 019", state: "CHAINED", detail: "References checkpoint 018", tone: "ok" },
      { source: "Verification", state: "FAILED", detail: "Break located at window 018", tone: "danger" },
    ],
    steps: [
      { time: "13:20:01", label: "Resolution recorded", detail: "Actor, outcome, reason and correlation id are written to the audit register.", source: "Audit", tone: "ok" },
      { time: "13:30:00", label: "Window sealed", detail: "Raw stored values are length-prefixed, ordered and hashed into checkpoint 018.", source: "Checkpoint", tone: "ok" },
      { time: "14:11:26", label: "Stored row altered", detail: "A database-level edit changes the resolution reason after sealing.", source: "Attack replay", tone: "danger" },
      { time: "14:30:00", label: "New checkpoint created", detail: "The next window links to the preceding checkpoint; it does not replace it.", source: "Checkpoint", tone: "ok" },
      { time: "14:31:04", label: "Chain verification fails", detail: "The original window digest no longer matches its rows; the incident is not reported as verified.", source: "TrustLedger", tone: "danger" },
    ],
    proof: "Nine real-PostgreSQL attack tests cover edit, delete, back-date and attempted re-sealing; digest-column mutation turns the test red.",
  },
  {
    id: "provider-timeout",
    number: "06",
    title: "Provider timeout / ambiguous state",
    shortTitle: "Provider timeout",
    question: "The network times out after submission. Did the provider accept the money movement?",
    amount: "£7,600.00",
    exposure: "£7,600.00 pending truth",
    classification: "PENDING_UNKNOWN",
    severity: "HIGH",
    control: "Fail-safe provider handling",
    conclusion: "The timeout does not prove rejection. TrustLedger retains PENDING_UNKNOWN and holds the reservation pending provider reconciliation.",
    explanation: "A retry cannot be treated as harmless until the original provider outcome is known. Unknown is a first-class financial state.",
    sources: [
      { source: "Internal ledger", state: "RESERVED", detail: "£7,600.00 held", tone: "unknown" },
      { source: "Provider A", state: "NO RESPONSE", detail: "Transport timeout after submit", tone: "unknown" },
      { source: "Webhook inbox", state: "NO EVENT", detail: "No callback received yet", tone: "unknown" },
      { source: "Settlement file", state: "PENDING", detail: "Next statement not received", tone: "unknown" },
    ],
    steps: [
      { time: "15:06:31", label: "Funds reserved", detail: "Availability is reduced without posting a final movement.", source: "Ledger", tone: "ok" },
      { time: "15:06:32", label: "Submission sent", detail: "Idempotency identity and provider correlation are retained.", source: "TrustLedger", tone: "ok" },
      { time: "15:07:02", label: "Transport timeout", detail: "No definitive provider response arrives within the request window.", source: "Provider A", tone: "unknown" },
      { time: "15:07:02", label: "PENDING_UNKNOWN assigned", detail: "TrustLedger refuses to manufacture success or failure.", source: "State policy", tone: "warning" },
      { time: "15:12:02", label: "Reconciliation required", detail: "Provider truth must be pulled before release, retry or final posting.", source: "Reconciliation", tone: "warning" },
    ],
    proof: "External payment integration tests assert timeout → PENDING_UNKNOWN and controlled late settlement.",
  },
];

const PROOF_REGISTER = [
  { label: "Financial state", value: "Deterministic", detail: "AI cannot establish or overwrite financial truth." },
  { label: "Evidence", value: "Ed25519", detail: "Exact stored bytes can be independently verified." },
  { label: "Audit history", value: "Tamper-evident", detail: "Sealed checkpoint verification detects mutation." },
  { label: "Pilot posture", value: "Read-only", detail: "Observe, explain and record; no customer-money execution." },
];

const CAPABILITY_REGISTER = [
  {
    area: "Financial core",
    status: "TEST-BACKED MAIN",
    proof: "Double-entry posting, reserve / release / reverse semantics, idempotency and explicit PENDING_UNKNOWN.",
    boundary: "Full balance reconstruction is not claimed while opening balances can exist outside the journal.",
  },
  {
    area: "Reconciliation",
    status: "TEST-BACKED MAIN",
    proof: "Settlement ingestion, source comparison, duplicate and currency breaks, SLA escalation and historical fee reconciliation.",
    boundary: "Customer-scale investigation speed, recall and ROI remain unmeasured.",
  },
  {
    area: "Audit & evidence",
    status: "TEST-BACKED MAIN",
    proof: "Append-only records, sealed checkpoint chains, attack detection and Ed25519 evidence signatures.",
    boundary: "External checkpoint anchoring and per-tenant signing keys are not built.",
  },
  {
    area: "Tenant security",
    status: "TEST-BACKED MAIN",
    proof: "Tenant and organisation-unit scope, scoped locking, cross-tenant tests and enterprise OIDC boundaries.",
    boundary: "Security controls are engineering evidence, not a production certification claim.",
  },
  {
    area: "Provider controls",
    status: "TEST-BACKED MAIN",
    proof: "Rail abstraction, Paystack work, credentials, rotation, emergency stop and certification drills.",
    boundary: "Stripe proves adapter structure; its live transport is not certified.",
  },
  {
    area: "Fraud & ML",
    status: "TEST-BACKED MAIN",
    proof: "Explainable risk bands, hold / review controls, signal evidence and governed ML shadow mode.",
    boundary: "ML can inform operators; it cannot establish financial truth.",
  },
  {
    area: "Recovery",
    status: "DEVELOPMENT VERIFIED",
    proof: "A destructive PostgreSQL restore rechecked financial, tenant, idempotency and audit invariants.",
    boundary: "Off-host backup, PITR and post-restore provider reconciliation are not production-proven.",
  },
  {
    area: "Operations UI",
    status: "OPEN PR + LOCAL",
    proof: "A substantial responsive console exists; broader frontend/API integration remains in open PR #125.",
    boundary: "Open-PR and local-verified work are not represented as canonical remote main.",
  },
  {
    area: "Commercial proof",
    status: "UNPROVEN",
    proof: "The interview and paid-pilot gates are pre-registered and mechanically scored.",
    boundary: "0/25 interviews, 0/3 data commitments and 0/2 paid commitments are currently recorded.",
  },
];

export default function ShowcasePage() {
  const [scenarioId, setScenarioId] = useState("fee-overcharge");
  const scenario = useMemo(() => SCENARIOS.find((item) => item.id === scenarioId) ?? SCENARIOS[3], [scenarioId]);
  const [visibleStep, setVisibleStep] = useState(scenario.steps.length - 1);
  const [playing, setPlaying] = useState(false);

  useEffect(() => {
    setVisibleStep(scenario.steps.length - 1);
    setPlaying(false);
  }, [scenario]);

  useEffect(() => {
    if (!playing) return;
    if (visibleStep >= scenario.steps.length - 1) {
      setPlaying(false);
      return;
    }
    const timer = window.setTimeout(() => setVisibleStep((step) => step + 1), 680);
    return () => window.clearTimeout(timer);
  }, [playing, scenario.steps.length, visibleStep]);

  function replay() {
    setVisibleStep(0);
    setPlaying(true);
  }

  const complete = visibleStep === scenario.steps.length - 1;

  return (
    <div className="public-showcase">
      <a href="#showcase-main" className="skip-link">Skip to showcase</a>
      <header className="public-showcase-nav">
        <a className="public-showcase-brand" href="/showcase" aria-label="TrustLedger executive showcase">
          <span className="public-showcase-mark" aria-hidden>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <path d="M6 4h12v16H6z" />
              <path d="M9 8h6M9 12h6M9 16h3" />
              <path d="m14.5 15.5 1.5 1.5 3-3" />
            </svg>
          </span>
          <span><b>TrustLedger</b><small>Payment reliability</small></span>
        </a>
        <div className="public-showcase-nav-actions">
          <span className="public-showcase-mode"><i aria-hidden /> Public synthetic replay</span>
          <a className="btn secondary" href="/login">Open secure console</a>
        </div>
      </header>

      <main className="public-showcase-main" id="showcase-main">
      <header className="showcase-header">
        <div>
          <p className="eyebrow">Executive showcase · synthetic incident replay</p>
          <h1>When systems disagree, reconstruct the money.</h1>
          <p className="sub">One controlled story makes the existing reliability controls visible. Every record below is fictional; every named control is mapped to repository test evidence.</p>
        </div>
        <div className="showcase-actions">
          <span className="showcase-boundary">NO CUSTOMER DATA · NO MONEY MOVEMENT</span>
          <button onClick={replay} disabled={playing}>{playing ? "Replaying…" : "Replay incident"}</button>
        </div>
      </header>

      <section className="showcase-proof-strip" aria-label="Showcase proof boundary">
        {PROOF_REGISTER.map((item) => (
          <article key={item.label}>
            <span>{item.label}</span>
            <strong>{item.value}</strong>
            <small>{item.detail}</small>
          </article>
        ))}
      </section>

      <section className="scenario-rail" aria-label="Incident scenarios">
        {SCENARIOS.map((item) => (
          <button
            key={item.id}
            className={item.id === scenario.id ? "active" : ""}
            onClick={() => setScenarioId(item.id)}
            aria-pressed={item.id === scenario.id}
          >
            <span>{item.number}</span>
            <b>{item.shortTitle}</b>
          </button>
        ))}
      </section>

      <section className="incident-docket">
        <div className="docket-title">
          <div>
            <p className="eyebrow">Incident TL-DEMO-{scenario.number}</p>
            <h2>{scenario.title}</h2>
            <p>{scenario.question}</p>
          </div>
          <div className="docket-status">
            <span className={`showcase-severity ${scenario.severity.toLowerCase()}`} title="Synthetic demo priority, not a customer SLA">{scenario.severity}</span>
            <span className="mono">{scenario.classification}</span>
          </div>
        </div>
        <div className="docket-measures">
          <div><span>Gross payment</span><strong>{scenario.amount}</strong></div>
          <div><span>Current exposure</span><strong>{scenario.exposure}</strong></div>
          <div><span>Control applied</span><strong>{scenario.control}</strong></div>
          <div><span>Control result</span><strong>{complete ? scenario.classification : `EVIDENCE ${visibleStep + 1}/${scenario.steps.length}`}</strong></div>
        </div>
      </section>

      <div className="showcase-workbench">
        <section className="panel source-board">
          <div className="panelHeader">
            <div><p className="eyebrow">01 · Source records</p><h2>Systems do not agree</h2></div>
            <span className="mono muted">PRESERVED AS RECEIVED</span>
          </div>
          <div className="source-grid">
            {scenario.sources.map((source) => (
              <article className={`source-record ${source.tone}`} key={source.source}>
                <span>{source.source}</span>
                <strong>{source.state}</strong>
                <small>{source.detail}</small>
              </article>
            ))}
          </div>
        </section>

        <section className="panel reconstruction-board">
          <div className="panelHeader">
            <div><p className="eyebrow">02 · Reconstruction</p><h2>Canonical incident timeline</h2></div>
            <span className="mono muted">UTC · CORRELATED</span>
          </div>
          <ol className="incident-timeline">
            {scenario.steps.map((step, index) => (
              <li className={`${step.tone}${index <= visibleStep ? " visible" : " future"}`} key={`${step.time}-${step.label}`}>
                <time>{step.time}</time>
                <span className="timeline-marker" aria-hidden />
                <div>
                  <span className="timeline-source">{step.source}</span>
                  <strong>{step.label}</strong>
                  <p>{step.detail}</p>
                </div>
              </li>
            ))}
          </ol>
        </section>

        <aside className="panel conclusion-board">
          <div className="panelHeader"><div><p className="eyebrow">03 · Supported conclusion</p><h2>What happened to the money?</h2></div></div>
          <div className="conclusion-body">
            <div className={`conclusion-signal ${complete ? "ready" : "working"}`}>
              <span>{complete ? "SUPPORTED" : "RECONSTRUCTING"}</span>
              <b>{complete ? scenario.classification.replace(/_/g, " ") : "SOURCE REVIEW IN PROGRESS"}</b>
            </div>
            <p className="conclusion-copy">{complete ? scenario.conclusion : "TrustLedger is correlating the visible source records. No final state is inferred before the evidence supports it."}</p>
            <div className="policy-note">
              <span>Decision boundary</span>
              <p>{scenario.explanation}</p>
            </div>
            <dl className="evidence-dossier">
              <div><dt>Pack</dt><dd className="mono">EVP-TL-DEMO-{scenario.number}</dd></div>
              <div><dt>Integrity</dt><dd>SHA-256 + Ed25519</dd></div>
              <div><dt>Signing key</dt><dd className="mono">demo-key-2026-08</dd></div>
              <div><dt>Customer proof</dt><dd>NOT YET ESTABLISHED</dd></div>
            </dl>
            <div className="test-proof">
              <span>Repository evidence</span>
              <p>{scenario.proof}</p>
            </div>
          </div>
        </aside>
      </div>

      <section className="showcase-honesty">
        <div>
          <p className="eyebrow">Proven in the current engineering evidence</p>
          <ul>
            <li>Reconciliation mechanics and explicit financial ambiguity</li>
            <li>Balanced ledger, idempotency and tenant-scoped controls</li>
            <li>Fee-schedule comparison, evidence signatures and audit tamper detection</li>
            <li>Development restore with financial-integrity validation</li>
          </ul>
        </div>
        <div>
          <p className="eyebrow warning-text">Not yet customer-proven</p>
          <ul>
            <li>Customer ROI, production traffic or reference customers</li>
            <li>Production-scale availability and recovery</li>
            <li>Live Stripe transport certification</li>
            <li>The market and six-week product gates</li>
          </ul>
        </div>
      </section>

      <details className="capability-register">
        <summary>
          <span><b>Inspect the technical evidence register</b><small>Nine capability areas with their current proof boundary</small></span>
          <span className="mono">MAIN · OPEN PR · LOCAL · UNPROVEN</span>
        </summary>
        <div className="capability-register-head" aria-hidden>
          <span>Capability</span><span>Current state</span><span>What the repository proves</span><span>Boundary</span>
        </div>
        <div className="capability-register-body">
          {CAPABILITY_REGISTER.map((item) => (
            <article key={item.area}>
              <strong>{item.area}</strong>
              <span className={`capability-status ${item.status === "UNPROVEN" ? "unproven" : item.status.includes("LOCAL") ? "local" : "verified"}`}>{item.status}</span>
              <p>{item.proof}</p>
              <p className="capability-boundary">{item.boundary}</p>
            </article>
          ))}
        </div>
      </details>
      </main>

      <footer className="public-showcase-footer">
        <span>TrustLedger executive showcase</span>
        <span>Synthetic records only · No customer data · No financial execution</span>
      </footer>
    </div>
  );
}
