# Demo Script (12–15 min)

Audience: a mixed buyer + technical + risk panel. Every step shows real product behaviour — nothing
is faked. Reset between runs by creating a fresh tenant (`pilot/demo-seed.sh`).

## 0. Setup (before the call)
Run `pilot/demo-seed.sh` against the demo instance → a clean tenant with accounts, completed
transfers, and a printed explainable risk assessment. Log in with the seeded owner credentials.
(See the honest note in `HOSTED_DEMO.md`: held cases aren't auto-created by the public transfer
endpoint yet — show fraud/evidence via the assessment + `pilot/sample-evidence/`.)

## 1. The promise (1 min)
"TrustLedger is a ledger that's correct under load, fraud you can explain, and evidence you can hand
an auditor — multi-tenant from day one. Everything I show is backed by automated tests on green CI."

## 2. The ledger is correct (3 min)
- Show the Accounts + Transfers pages; make a transfer; show it post (double-entry).
- Talk to the proof: "50 concurrent transfers against one account can't overspend — here's the test."
  (Show `HardeningIntegrationTest` green in CI.) "Debits always equal credits."

## 3. Explainable fraud (3 min)
- Show the risk assessment the seed printed (decision + signals), then explain the layers: rules →
  behavioural intelligence → ML. On the **ML** page, walk the model + governance (shadow / analyst-assist).
- Talk to a stored score's **ranked contributing factors** ("amount 12× the user's median, beneficiary
  added 1.7h ago, untrusted device") using `pilot/sample-evidence/` if no live held case exists.
- Key line: "The ML model runs in **shadow mode** — it raises visibility, it never moves money. The
  rules engine and the analyst stay in control." (Promotion to blocking is structurally rejected.)

## 4. Investigation-ready evidence (2 min)
- Open `pilot/sample-evidence/fraud-case-evidence.sample.json` — the exact shape the evidence engine
  produces: case + signals + linked cases + transfer + a `sha256:` checksum.
- "It's SHA-256 checksummed, audited, tenant-scoped, and can be put under **legal hold** so it can't
  be deleted — verified by tests." Show the ledger proof sample (debits == credits).

## 5. Multi-tenant SaaS controls (2 min)
- Admin page: show plan, usage metering, quotas, per-tenant fraud policy.
- "A VIEWER can't export evidence — and the denial is audited. Tenant A can never see tenant B's data."

## 6. Operations & deployment (2 min)
- Show the Grafana dashboard + SLOs; mention the verified backup/restore drill.
- "Deploy via Helm, Kustomize, or Terraform — all validated in CI. Secrets come from a secret manager,
  never the image."

## 7. Close (1 min)
- "It's pilot-ready, not a regulated bank — we're upfront about the regulatory boundary. Here's a
  4–6 week pilot on your data." → [PILOT_CHECKLIST.md](PILOT_CHECKLIST.md).

## Live attack (optional, if asked)
Send a port-scan-style burst / a new-device + new-beneficiary + high-amount transfer to show the
fraud case open + the ML explanation update in real time.
