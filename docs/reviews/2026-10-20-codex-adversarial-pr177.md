# Codex adversarial review of PR #177 (re-derivable case bundle)

Run 2026-10-20 with `~/.codex/adversarial-review-prompt.md` against branch `feat/rederive-verifier` at `3a33d46`,
model `gpt-5.6-sol`, sandbox `workspace-write`. The reviewer's text below is verbatim. What was done with it:

- **Its finding stands and is accepted.** The verifier trusted each record's `currency`; only amounts were
  checked against the source row. Changing a finding and both its records from GBP to USD, with the hash
  recomputed and all four genuine source files supplied, exited 0 with "7 reproduced". A wrong currency on a
  correct number corrupts currency-separated exposure.
- **Its correction was amended before commit.** It located the currency by a fixed column position per
  profile. TrustLedger reads columns by header name (`CsvTable` lower-cases the header; profiles declare
  `requiredHeaders`), so a valid file with reordered columns would have made the verifier fail an honest
  bundle. The committed version finds the column by name in the supplied file's own header, and without the
  file only requires the code to appear in the row (exit 3 if it does not). Observed: with the positional
  reading restored, the new reordered-file control fails (`its currency column says PAYMENT`); with the
  header reading it passes. Controls 19/19.
- **Its second objection is a correction to the claims, not the code.** The provider bundle verified locally
  holds internal, Adyen transaction and Adyen settlement sources; no Checkout.com source. The tracker now says
  so, and that the Checkout.com currency column is implemented but unexercised by any exported bundle.
- **Its "could not check" items are sandbox artefacts.** Docker was denied, so Maven reported 12 run, 12
  skipped; GitHub API access failed. CI on `3a33d46` ran 685 tests with nothing skipped and all 8 jobs green.

---

MOST DANGEROUS FALSE ASSUMPTION / UNVERIFIABLE CLAIM:
  the claim, quoted
  “The verifier … re-derives every exception’s currency and amount …; it never reports a finding as passed that it could not check.”

  why it is unsupported
  At 3a33d46, `rederive()` trusted `findingInputs.records[].currency`; provenance checked only numeric values. I changed an AMOUNT_MISMATCH finding and both referenced records from GBP to USD, recomputed `contentHash`, and supplied all four authentic source files. The verifier exited 0 and reported “7 reproduced” including the forged USD finding.

  what evidence would settle it
  A negative control changing the finding and record currencies together must fail by independently reading the profile-specific currency column from each raw source row.

  what breaks if it is wrong
  A bundle can certify a correct number under the wrong currency. That corrupts financial exposure, currency-separated totals, and the audit conclusion while presenting an exit-0 independent-verification result.

CLAIMS CHECKED AND CONFIRMED:      (with the command that confirmed each)
  - Branch/base are 3a33d46/96d07c4 and the initial worktree was clean: `git status --short --branch`; `git rev-parse HEAD origin/main`.
  - `findingInputs` is inside hashed `content`; changing it without rehashing fails: `python3 scripts/verify_recon_bundle.py /tmp/trustledger-unhashed-finding-input.json` → exit 1.
  - `CaseBundleService` emits referenced records, raw rows and fee schedules; `recordsByKeys` is tenant/case scoped and requires completed imports: repository source inspection.
  - ACME artifact plus four source files currently produces `7 reproduced, 0 not re-derivable, 0 failed` and exit 0: `python3 scripts/verify_recon_bundle.py backend/target/recon-bundle/acme-interim.json backend/src/test/resources/fixtures/acme-2026-08/*.csv`.
  - Provider artifact produces 56/56 and exit 3 with 24 unconfirmed Adyen fee sums. However, the report is false as written about “Adyen + Checkout.com”: that saved bundle contains only `internal-expected`, `provider-transactions`, and `adyen-settlement-detail`; no Checkout source.
  - The original 16 Python controls passed; after the correction, 17/17 pass: `python3 scripts/test_verify_recon_bundle.py`.
  - The negative-control script is wired into `.github/workflows/ci.yml`.
  - Stated limitations match source behavior: status transitions are not re-derived; missing-counterpart absence is not proven; status/reference parsing is trusted; timing is not measured.
  - The gitleaks finding is reproducibly generic-key detection over 64-hex run/record identities: direct fixture scan found 52 such matches. Scanning commits 66ac50b..3a33d46 with `.gitleaksignore` found no leaks.
  - Provider fixture digests match `docs/PROVIDER_SETTLEMENT_FORMATS.md`: Adyen `d178b561…eb7d74`, Checkout `6494c48e…876c4`.
  - Policy/repository gates pass: `scripts/validate_repo.py`; `scripts/check_continuity.py`; `scripts/check_continuity.py --selftest`; `git diff --check`.

CLAIMS THAT COULD NOT BE CHECKED:  (and why)
  - Fresh PostgreSQL claims were not verified. Docker access was denied; Maven reported 12 tests run, all 12 skipped. This is not a pass.
  - The claimed historical local result of 177 tests with zero skipped was not independently reproduced.
  - CI’s claimed 8/8 jobs, 685 tests and zero skips could not be inspected because GitHub API access failed. `FEATURE_TRACKER.md` also still labels the branch not CI-verified.
  - The Java `findingInputs`-omission mutant kill could not be rerun because its Testcontainers sentinel was skipped.
  - The fixtures’ documented provider provenance was not independently re-downloaded; only their repository bytes and documented digests were checked.

THE ONE BOUNDED CORRECTION:        (only after the above)
  `scripts/verify_recon_bundle.py` now reads currency from the source profile’s actual currency column for all five supported profiles. A mismatch fails provenance and prevents the affected finding from being counted as reproduced. The exact paired-currency forgery now exits 1, reports both GBP/USD source-row mismatches, and reports that finding as not re-derivable.

  Added the paired-currency regression control, raising the gate to 17/17, and updated `FEATURE_TRACKER.md` and `docs/EVIDENCE_BUNDLE_RECON_CASE.md`.

  Verification: 17/17 controls passed; the forged bundle changed from exit 0 to exit 1; intact ACME remains 7/7 exit 0; provider artifact remains 56/56 exit 3; repository and continuity gates pass.