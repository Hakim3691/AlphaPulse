#!/usr/bin/env python3
"""
Branch hygiene report - which branches are safe to prune.

Why this exists
---------------
Branches accumulate. `git branch -d` refuses an unmerged branch, and refusing is
correct: it is the only thing standing between a finished-but-unpushed idea and
the reflog. The cost is that a dead branch looks exactly like a live one, so the
list grows until the safe ones stop being obvious and the unsafe ones get deleted
by hand.

A branch is provably safe to delete when its work already exists on the base
branch, in one of two ways:

* **merged** - the base already contains the branch tip, so its commits are
  literally there;
* **superseded** - `git cherry` finds every one of the branch's non-merge commits
  already on the base as an equivalent patch (same patch-id), which is what a
  cherry-pick, a rebase or a re-applied change looks like from the outside.

Anything else still holds a commit that exists nowhere else, so it is reported as
KEEP together with the commits that are not on the base.

What this cannot tell you
-------------------------
Patch equivalence is checked; intent is not. A branch whose problem was later
fixed a different way on the base still reads as KEEP, because its patches
differ. That is deliberate - the script never calls a branch safe on a guess - so
a KEEP whose diff already looks obsolete is settled by reading the diff, not by
this report.

Usage
-----
    python3 scripts/branch_hygiene.py               # local branches vs main
    python3 scripts/branch_hygiene.py --remote      # also origin/* tracking refs
    python3 scripts/branch_hygiene.py --base origin/main

Exit codes: 0 when every branch still holds unique work, 1 when at least one
branch is safe to prune (so this can gate a cleanup), 2 on a usage or git error.
Nothing is deleted and no ref is modified - this only prints what it found.
"""

from __future__ import annotations

import os
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DEFAULT_BASE = "main"
DEFAULT_REMOTE = "origin"
MAX_LISTED_COMMITS = 6

MERGED = "merged"
SUPERSEDED = "superseded"
KEEP = "keep"


def git(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=False
    )


def git_out(*args: str) -> str | None:
    """Stdout of a git command, or None when it fails."""
    result = git(*args)
    return result.stdout.strip() if result.returncode == 0 else None


def refs(prefix: str) -> list[str]:
    out = git_out("for-each-ref", "--format=%(refname:short)", prefix)
    return [line.strip() for line in (out or "").splitlines() if line.strip()]


def is_ancestor(ancestor: str, descendant: str) -> bool:
    return git("merge-base", "--is-ancestor", ancestor, descendant).returncode == 0


def classify(base: str, branch: str) -> tuple[str, list[str]]:
    """Decide whether the branch holds work the base does not have.

    Returns the verdict plus the commits (short shas) that are still unique.
    """
    if is_ancestor(branch, base):
        return MERGED, []

    # `git cherry` marks each commit '+' when it is not on the base and '-' when
    # an equivalent patch already is. It ignores merge commits by design.
    upstream = 0
    unique: list[str] = []
    for line in (git_out("cherry", base, branch) or "").splitlines():
        marker, _, sha = line.partition(" ")
        sha = sha.strip()
        if not sha:
            continue
        if marker == "-":
            upstream += 1
        else:
            unique.append(sha)

    if unique:
        return KEEP, unique
    if upstream:
        return SUPERSEDED, []
    # Nothing but merge commits since the base. A merge can carry conflict
    # resolutions, so this is not called safe without a human reading it.
    return KEEP, []


def commit_subjects(shas: list[str]) -> list[str]:
    listed: list[str] = []
    for sha in shas[:MAX_LISTED_COMMITS]:
        subject = git_out("log", "-1", "--format=%h %s", sha) or sha
        listed.append(subject)
    if len(shas) > MAX_LISTED_COMMITS:
        listed.append(f"... and {len(shas) - MAX_LISTED_COMMITS} more")
    return listed


def main() -> int:
    base = DEFAULT_BASE
    include_remote = False
    remote = DEFAULT_REMOTE

    args = sys.argv[1:]
    index = 0
    while index < len(args):
        argument = args[index]
        if argument == "--remote":
            include_remote = True
            index += 1
        elif argument == "--base":
            if index + 1 >= len(args):
                print("branch_hygiene: --base needs a ref (for example --base main)", file=sys.stderr)
                return 2
            base = args[index + 1]
            index += 2
        elif argument in ("-h", "--help"):
            print(__doc__.strip())
            return 0
        else:
            print(f"branch_hygiene: unknown argument '{argument}' (see --help)", file=sys.stderr)
            return 2

    base_sha = git_out("rev-parse", "--short", "--verify", f"{base}^{{commit}}")
    if base_sha is None:
        print(f"branch_hygiene: '{base}' is not a commit in this repository", file=sys.stderr)
        return 2

    current = git_out("rev-parse", "--abbrev-ref", "HEAD") or ""

    candidates: list[tuple[str, str, str]] = []  # (kind, name, delete command)
    for name in refs("refs/heads"):
        if name == base:
            continue
        command = f"git branch -d {name}"
        if name == current:
            # A branch cannot be deleted while it is checked out.
            command = f"git switch {base} && {command}"
        candidates.append(("local", name, command))

    if include_remote:
        prefix = f"refs/remotes/{remote}"
        for tracking in refs(prefix):
            name = tracking[len(remote) + 1:] if tracking.startswith(f"{remote}/") else tracking
            if name == "HEAD" or tracking == base or name == base:
                continue
            candidates.append(("remote", name, f"git push {remote} --delete {name}"))

    safe: list[str] = []
    keep: list[str] = []

    for kind, name, delete_with in candidates:
        label = f"{name} ({kind})" if include_remote else name
        if kind == "local" and name == current:
            label += " [checked out]"
        ref = f"{remote}/{name}" if kind == "remote" else name
        verdict, unique = classify(base, ref)

        if verdict == MERGED:
            safe.append(f"  {label}: merged - {base} already contains this tip\n      {delete_with}")
        elif verdict == SUPERSEDED:
            count = git_out("rev-list", "--count", "--no-merges", f"{base}..{ref}") or "0"
            safe.append(
                f"  {label}: superseded - all {count} commit(s) are on {base} as equivalent patches\n"
                f"      {delete_with}"
            )
        else:
            if unique:
                details = "\n".join(f"      + {line}" for line in commit_subjects(unique))
                total = git_out("rev-list", "--count", "--no-merges", f"{base}..{ref}") or "?"
                keep.append(
                    f"  {label}: {len(unique)} of {total} commit(s) not on {base}\n{details}"
                )
            else:
                keep.append(
                    f"  {label}: only merge commits since {base}; check the merge resolutions "
                    "before deleting"
                )

    print(f"Branch hygiene against {base} @ {base_sha}")
    if include_remote:
        print(f"(local branches and {remote}/* tracking branches)")
    print()

    if safe:
        print(f"Safe to prune ({len(safe)})")
        print("\n".join(safe))
        print()
    if keep:
        print(f"Keep ({len(keep)})")
        print("\n".join(keep))
        print()
    if not candidates:
        print(f"No other branches to report against {base}.")
    elif not safe:
        print("Every branch still holds work that is not on the base; nothing to prune.")

    return 1 if safe else 0


if __name__ == "__main__":
    sys.exit(main())
