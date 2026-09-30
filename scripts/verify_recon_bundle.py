#!/usr/bin/env python3
"""Verify a TrustLedger reconciliation case bundle without TrustLedger.

    verify_recon_bundle.py bundle.json [source-file ...]

Checks that contentHash is the SHA-256 of the bundle's `content`, serialised compactly with keys in
document order. Any source files given are hashed and matched against the bundle's source manifests.
Exit 0 when everything checked is consistent, 1 otherwise.
"""
import hashlib
import json
import sys


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    with open(argv[1], "rb") as f:
        bundle = json.loads(f.read().decode("utf-8"))
    if bundle.get("kind") != "RECONCILIATION_CASE_EVIDENCE":
        print("FAIL not a reconciliation case bundle: kind=%r" % bundle.get("kind"))
        return 1
    # dicts keep document order; no floats are present because every amount is a string.
    compact = json.dumps(bundle["content"], separators=(",", ":"), ensure_ascii=False)
    actual = "sha256:" + hashlib.sha256(compact.encode("utf-8")).hexdigest()
    ok = actual == bundle.get("contentHash")
    print("%s contentHash %s" % ("OK  " if ok else "FAIL", bundle.get("contentHash")))
    if not ok:
        print("     recomputed  %s" % actual)

    declared = {s["fileSha256"]: s["filename"] for s in bundle["content"]["sources"]}
    for path in argv[2:]:
        with open(path, "rb") as f:
            digest = hashlib.sha256(f.read()).hexdigest()
        if digest in declared:
            print("OK   source %s is %s in the bundle" % (path, declared[digest]))
        else:
            print("FAIL source %s (%s) is not one of the bundle's %d sources" % (path, digest, len(declared)))
            ok = False

    c = bundle["content"]
    print("     status=%s ruleset=%s sources=%d matches=%d exceptions=%d" % (
        bundle.get("bundleStatus"), c["run"]["rulesetVersion"], len(c["sources"]), len(c["matches"]), len(c["exceptions"])))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
