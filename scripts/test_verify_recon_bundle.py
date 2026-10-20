#!/usr/bin/env python3
"""Negative controls for verify_recon_bundle.py: every check must be seen to fail.

    python3 scripts/test_verify_recon_bundle.py

The fixture is a real recon-case-bundle/2 export of the SYNTHETIC ACME-2026-08 case (CaseBundleIntegrationTest).
Each mutation below edits the bundle and then RECOMPUTES its contentHash, so the hash check passes and only the
provenance or re-derivation check stands between the edit and a green verdict.
"""
import copy
import hashlib
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
VERIFIER = os.path.join(HERE, "verify_recon_bundle.py")
FIXTURE = os.path.join(HERE, "fixtures", "acme-interim-bundle.FIXTURE.json")
SOURCES = os.path.join(HERE, "..", "backend", "src", "test", "resources", "fixtures", "acme-2026-08")


def rehash(bundle):
    compact = json.dumps(bundle["content"], separators=(",", ":"), ensure_ascii=False)
    bundle["contentHash"] = "sha256:" + hashlib.sha256(compact.encode("utf-8")).hexdigest()
    return bundle


def run(bundle, *sources):
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump(bundle, f, ensure_ascii=False)
    try:
        p = subprocess.run([sys.executable, VERIFIER, f.name, *sources], capture_output=True, text=True)
        return p.returncode, p.stdout
    finally:
        os.unlink(f.name)


def exception(b, t):
    return next(e for e in b["content"]["exceptions"] if e["type"] == t)


def record(b, key):
    return next(r for r in b["content"]["findingInputs"]["records"] if r["recordKey"] == key)


def main():
    with open(FIXTURE, encoding="utf-8") as f:
        intact = json.load(f)
    sources = [os.path.join(SOURCES, n) for n in
               ("internal-expected.csv", "provider-a-transactions.csv", "provider-b-transactions.csv", "provider-b-settlement.csv")]
    results = []

    def expect(name, code, needle, bundle, *srcs):
        got, out = run(bundle, *srcs)
        passed = got == code and needle in out
        results.append(passed)
        print("%s %-58s exit %s (want %s)" % ("PASS" if passed else "FAIL", name, got, code))
        if not passed:
            print(out)

    # Positive twin: the intact export reproduces all seven exceptions, with and without its source files.
    expect("intact bundle, all 7 reproduced", 0, "exceptions: 7 reproduced, 0 not re-derivable, 0 failed, of 7", intact)
    expect("intact bundle with its 4 source files", 0, "10 found in the source files supplied", intact, *sources)

    b = copy.deepcopy(intact)
    b["content"]["run"]["exceptionCount"] = 0
    expect("edited without rehashing", 1, "FAIL contentHash", b)

    b = copy.deepcopy(intact)
    exception(b, "FEE_MISMATCH")["exposureAmount"] = "600.0000"
    expect("exception amount inflated, rehashed", 1, "FAIL FEE_MISMATCH", rehash(b))

    b = copy.deepcopy(intact)
    exception(b, "AMOUNT_MISMATCH")["exposureCurrency"] = "NGN"
    expect("exception currency changed, rehashed", 1, "FAIL AMOUNT_MISMATCH", rehash(b))

    # The verifier must read currency from the source row, not trust matching edits to the finding and inputs.
    b = copy.deepcopy(intact)
    e = exception(b, "AMOUNT_MISMATCH")
    e["exposureCurrency"] = "USD"
    for ref in e["evidence"]["records"]:
        record(b, ref["recordKey"])["currency"] = "USD"
    expect("record and exception currency changed together", 1, "its currency column says GBP", rehash(b), *sources)
    expect("the same, without the source files: not confirmed", 3, "USD does not appear in the row", rehash(b))

    # TrustLedger reads columns by name, so a valid file may order them differently; the check must follow the header.
    with open(sources[0], encoding="utf-8") as f:
        lines = f.read().splitlines()
    swap = lambda line: ",".join((lambda x: [x[4]] + x[:4] + x[5:])(line.split(",")))
    with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
        f.write("\n".join(swap(l) for l in lines) + "\n")
    try:
        b = copy.deepcopy(intact)
        digest = hashlib.sha256(open(f.name, "rb").read()).hexdigest()
        old = next(s for s in b["content"]["sources"] if s["profile"] == "internal-expected")["fileSha256"]
        for s in b["content"]["sources"]:
            if s["fileSha256"] == old:
                s["fileSha256"] = digest
        for r in b["content"]["findingInputs"]["records"]:
            if r["fileSha256"] == old:
                r["fileSha256"] = digest
                r["rawRow"] = swap(r["rawRow"])
                r["rowSha256"] = hashlib.sha256("".join(x + "\x1f" for x in (r["sourceType"], r["sourceIdentity"], r["rawRow"])).encode()).hexdigest()
        keys = {r["recordKey"]: r["rowSha256"] for r in b["content"]["findingInputs"]["records"]}
        for e in b["content"]["exceptions"]:
            for ref in e["evidence"]["records"]:
                ref["rowSha256"] = keys.get(ref["recordKey"], ref["rowSha256"])
        expect("currency column first in a reordered file is still read", 0, "7 reproduced", rehash(b), f.name)
    finally:
        os.unlink(f.name)

    b = copy.deepcopy(intact)
    record(b, exception(b, "AMOUNT_MISMATCH")["evidence"]["records"][0]["recordKey"])["grossAmount"] = "81.0000"
    expect("record amount changed, rehashed", 1, "FAIL AMOUNT_MISMATCH", rehash(b))

    b = copy.deepcopy(intact)
    r = record(b, exception(b, "MISSING_PROVIDER_RECORD")["evidence"]["records"][0]["recordKey"])
    r["rawRow"] = r["rawRow"].replace("15000", "16000")
    expect("source row text changed, rehashed", 1, "does not hash to its rowSha256", rehash(b))

    # A forger who rewrites a row AND its hash passes the hash check; the source file is what catches it.
    b = copy.deepcopy(intact)
    r = record(b, exception(b, "MISSING_PROVIDER_RECORD")["evidence"]["records"][0]["recordKey"])
    r["rawRow"] = r["rawRow"].replace("15000", "16000")
    r["rowSha256"] = hashlib.sha256("".join(f + "\x1f" for f in (r["sourceType"], r["sourceIdentity"], r["rawRow"])).encode()).hexdigest()
    for e in b["content"]["exceptions"]:
        for ref in e["evidence"]["records"]:
            if ref["recordKey"] == r["recordKey"]:
                ref["rowSha256"] = r["rowSha256"]
    expect("row and its hash rewritten, caught by the source file", 1, "not in the source file supplied", rehash(b), *sources)

    # Amount and exception changed together re-derive consistently; the row no longer carries the amount.
    b = copy.deepcopy(intact)
    e = exception(b, "MISSING_PROVIDER_RECORD")
    record(b, e["evidence"]["records"][0]["recordKey"])["grossAmount"] = "16000.0000"
    e["exposureAmount"] = "16000.0000"
    expect("record and exception changed together, not confirmed", 3, "is not a value in the row", rehash(b))

    b = copy.deepcopy(intact)
    b["content"]["findingInputs"]["feeSchedules"][0]["percentageBps"] = 200
    expect("fee schedule changed, rehashed", 1, "FAIL FEE_MISMATCH", rehash(b))

    b = copy.deepcopy(intact)
    key = exception(b, "REFUND_MISMATCH")["evidence"]["records"][0]["recordKey"]
    b["content"]["findingInputs"]["records"] = [x for x in b["content"]["findingInputs"]["records"] if x["recordKey"] != key]
    expect("an exception's record removed, rehashed", 1, "not listed in the bundle", rehash(b))

    b = copy.deepcopy(intact)
    exception(b, "LATE_SETTLEMENT")["exposureAmount"] = "1.0000"
    expect("late settlement given an amount at risk, rehashed", 1, "FAIL LATE_SETTLEMENT", rehash(b))

    b = copy.deepcopy(intact)
    del b["content"]["findingInputs"]
    expect("a /2 bundle without its inputs, rehashed", 1, "cannot be re-derived", rehash(b))

    b = copy.deepcopy(intact)
    exception(b, "REFUND_MISMATCH")["type"] = "SOME_FUTURE_TYPE"
    expect("unknown finding type is not passed", 3, "1 not re-derivable", rehash(b))

    b = copy.deepcopy(intact)
    b["content"]["run"]["rulesetVersion"] = "recon-rules/9.9.9"
    expect("unknown ruleset is not passed", 3, "re-derivation skipped", rehash(b))

    with tempfile.NamedTemporaryFile("wb", suffix=".csv", delete=False) as f:
        f.write(open(sources[0], "rb").read().replace(b"15000", b"16000"))
    try:
        expect("a different source file is refused", 1, "is not one of the bundle's", intact, f.name)
    finally:
        os.unlink(f.name)

    if len(results) < 19:
        print("FAIL only %d controls ran" % len(results))
        return 1
    print("%d of %d controls behaved" % (sum(results), len(results)))
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
