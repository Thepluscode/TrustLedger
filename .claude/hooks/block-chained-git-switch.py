#!/usr/bin/env python3
"""PreToolUse guard: refuse a `git switch`/`checkout` chained to another git subcommand.

Raises the "never chain a state-changing git command behind a switch" rule in CLAUDE.md from
level 2 (documented) to level 4 (structurally prevented).

Why this specific shape is worth blocking: `git switch` REFUSES when the working tree conflicts with
the target branch, and exits non-zero. Anything joined with `;` then runs on the branch you were
already on. It fired twice in one session — once landing a doc edit on the wrong branch while the
commit message claimed otherwise, once letting a `git stash pop` grab an unrelated stash and merge
obsolete edits into money-path files.

Deliberately narrow. It blocks only a branch change followed by ANOTHER git subcommand in the same
line. `git switch X && npm test` is fine — a failed switch makes the test fail visibly, and the test
does not silently mutate repository state. The danger is specifically git-state mutation attributed
to the wrong branch.

KNOWN FALSE POSITIVE, accepted rather than papered over: the pattern is matched textually, so a
command that merely *quotes* it — `echo "git switch main; git stash pop"`, or writing this rule into
a file — is blocked too. Correctly distinguishing executed text from quoted text needs a shell parser,
and a half-correct one would fail in the direction that matters (letting a real chain through). The
cost of the false positive is retyping one echo; the cost of a false negative is a commit on the wrong
branch. Erring toward blocking is the right trade here, and saying so is better than a regex that
pretends to be precise.

Self-test:  python3 block-chained-git-switch.py --selftest
"""
import json
import re
import sys

# A branch change, then a separator, then another git subcommand.
CHAINED = re.compile(
    r"\bgit\s+(?:switch|checkout)\b[^;&|]*"      # the branch change
    r"(?:;|&&|\|\|)\s*"                          # any chaining separator
    r"git\s+(?:stash|commit|push|cherry-pick|rebase|merge|reset|add|am|revert)\b",
    re.IGNORECASE,
)

MESSAGE = (
    "Blocked: a `git switch`/`checkout` is chained to another git command.\n"
    "`git switch` REFUSES when the working tree conflicts with the target branch and exits non-zero, "
    "so the command after it runs on the branch you were already on.\n"
    "This has caused a commit on the wrong branch and a `stash pop` of an unrelated stash into "
    "money-path files.\n"
    "Run the branch change alone, confirm with `git branch --show-current`, then run the rest.\n"
    "See CLAUDE.md -> 'Never chain a state-changing git command behind one that can silently refuse'."
)


def is_blocked(command: str) -> bool:
    return bool(command) and bool(CHAINED.search(command))


def selftest() -> int:
    must_block = [
        "git switch main; git stash pop",
        "git switch -c feat/x origin/main && git commit -m 'x'",
        "git checkout main && git push",
        "git switch feat/a ; git cherry-pick abc123",
        "cd repo && git switch main && git stash pop",
        "git switch main || git reset --hard",
    ]
    must_allow = [
        "git switch main",                              # the safe form this rule asks for
        "git stash pop",                                # alone
        "git switch main && npm test",                  # non-git follow-up: failure is visible
        "git status && git diff",                       # no branch change
        "git log --oneline -5",
        "git commit -m 'switch to new approach'",       # 'switch' only in the message
    ]
    # Documented false positives: matched textually, blocked, and accepted. Asserted so that if the
    # matching ever becomes quote-aware, this list fails and the docstring gets corrected with it.
    known_false_positives = [
        "echo 'git switch main; git stash pop'",
    ]
    failures = 0
    for c in must_block:
        if not is_blocked(c):
            print(f"  FAIL should block: {c}")
            failures += 1
    for c in must_allow:
        if is_blocked(c):
            print(f"  FAIL should allow: {c}")
            failures += 1
    for c in known_false_positives:
        if not is_blocked(c):
            print(f"  FAIL known false positive no longer fires (update the docstring): {c}")
            failures += 1
    total = len(must_block) + len(must_allow) + len(known_false_positives)
    print(f"selftest: {total - failures}/{total} cases correct")
    if failures:
        print("SELFTEST FAILED")
        return 1
    print("SELFTEST PASSED")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0  # Never break the session on a malformed payload; fail open, not closed.
    if payload.get("tool_name") != "Bash":
        return 0
    if is_blocked(payload.get("tool_input", {}).get("command", "")):
        print(MESSAGE, file=sys.stderr)
        return 2  # non-zero + stderr => the tool call is blocked and the reason is shown
    return 0


if __name__ == "__main__":
    sys.exit(main())
