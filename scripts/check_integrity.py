#!/usr/bin/env python3
"""
Repository integrity checks - the guard that stops a corrupted tree from being
committed again.

Why this exists
---------------
The codebase was once left in a state where the same class was declared twice,
merge-conflict markers were committed, and stray editor backups sat in the
source tree. Kotlin then failed with hundreds of "conflicting overloads" errors
that hid the real cause. Each failure below is cheap to detect and expensive to
diagnose, so they are now checked before every commit and on every CI run.

What it checks
--------------
1. No merge-conflict markers anywhere in tracked source.
2. No editor/backup leftovers (*.orig, *.rej, *.bak, *~).
3. No Kotlin file declaring the same top-level type twice in the same package.
4. No Kotlin file that is empty or lost its `package` line.
5. The spec traceability matrix still covers every section 1-64.
6. The vendored Cactus Needle 3 engine binaries are byte-for-byte the published
   ones, so a truncated or edited archive cannot reach a device (§30).
7. The finalized-behaviour registry (FINALIZED_CONTRACTS.md) still exists, still
   lists its contracts, and every guard spec it names is present - so a decision
   cannot be reverted by deleting its row or its test.

Usage
-----
    python3 scripts/check_integrity.py          # check everything
    python3 scripts/check_integrity.py --staged # check only staged files (hook)

Exit code 0 means the tree is healthy; 1 means at least one problem was found
and printed above.
"""

from __future__ import annotations

import hashlib
import os
import re
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SOURCE_SUFFIXES = (".kt", ".kts", ".java", ".xml", ".yml", ".yaml", ".py")
SKIP_DIRS = {".git", "build", ".gradle", "node_modules", ".idea", ".kotlin"}

CONFLICT_MARKERS = ("<<<<<<<", "=======", ">>>>>>>")
BACKUP_SUFFIXES = (".orig", ".rej", ".bak", "~")

# A top-level Kotlin declaration, e.g. "class Foo", "object Bar", "data class Baz(".
DECL_PATTERN = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public\s+|internal\s+|private\s+|open\s+|abstract\s+|sealed\s+|data\s+|"
    r"enum\s+|annotation\s+|value\s+|inline\s+|expect\s+|actual\s+)*"
    r"(class|interface|object|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)
PACKAGE_PATTERN = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)

# ---------------------------------------------------------------------------
# Vendored native engine fingerprints (Cactus-Compute/needle3 on Hugging Face).
#
# These archives are linked into the APK, so they are not covered by any source
# check. Pinning their size and digest here means a corrupt download, a partial
# checkout or a hand-edited archive fails the commit instead of shipping. The
# same values are asserted by the unit tests, from the other direction.
# ---------------------------------------------------------------------------
VENDORED_ENGINE = {
    "app/src/main/cpp/engine/needle.h": (
        4528,
        "a27dac4b3239eead91d124b7f7dbce5495c13019903fd817b68c16d39b055a99",
    ),
    "app/src/main/cpp/engine/android-arm64/libneedle.a": (
        2177676,
        "7aa6de6132a22bf45f5e903425feb66f159ad46f47fa558921bc4fd02c3a2fa0",
    ),
    "app/src/main/cpp/engine/android-armv7/libneedle.a": (
        1700760,
        "9f559a287b201c213e5c753d3cd1076a1849c73f94b445c85d11e4969422a135",
    ),
}

SMART_QUOTES = {
    "\u2018": "'", "\u2019": "'", "\u201c": '"', "\u201d": '"', "\u2013": "-", "\u2014": "-",
}

problems: list[str] = []


def fail(message: str) -> None:
    problems.append(message)


def walk_source_files() -> list[str]:
    """Every source-ish file in the repo, skipping build output and VCS data."""
    found: list[str] = []
    for root, dirs, files in os.walk(REPO_ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in files:
            if name.endswith(SOURCE_SUFFIXES):
                found.append(os.path.join(root, name))
    return found


def staged_files() -> list[str]:
    """Files git is about to commit (used by the pre-commit hook)."""
    out = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACM"],
        cwd=REPO_ROOT, capture_output=True, text=True, check=False,
    ).stdout
    return [os.path.join(REPO_ROOT, line) for line in out.splitlines() if line.strip()]


def relative(path: str) -> str:
    return os.path.relpath(path, REPO_ROOT)


def check_conflict_markers(paths: list[str]) -> None:
    for path in paths:
        if not path.endswith(SOURCE_SUFFIXES):
            continue
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                for number, line in enumerate(handle, start=1):
                    stripped = line.strip()
                    # "=======" alone is legal markdown underlining, so require
                    # the full seven-character conflict form at line start.
                    if stripped.startswith(("<<<<<<<", ">>>>>>>")) or stripped == "=======":
                        fail(f"{relative(path)}:{number}: merge-conflict marker '{stripped[:7]}'")
        except OSError as error:
            fail(f"{relative(path)}: could not be read ({error})")


def check_backup_files(paths: list[str]) -> None:
    for path in paths:
        if path.endswith(BACKUP_SUFFIXES):
            fail(f"{relative(path)}: editor/backup leftovers must not be committed")


def check_kotlin_files(paths: list[str]) -> None:
    declared: dict[tuple[str, str], str] = {}

    for path in paths:
        if not path.endswith((".kt", ".kts")):
            continue
        with open(path, encoding="utf-8", errors="replace") as handle:
            text = handle.read()

        if not text.strip():
            fail(f"{relative(path)}: file is empty")
            continue

        package_match = PACKAGE_PATTERN.search(text)
        if not package_match:
            # Gradle scripts legitimately have no package; Kotlin sources do.
            if not path.endswith(".kts"):
                fail(f"{relative(path)}: missing 'package' declaration")
            continue
        package = package_match.group(1)

        # Remove block comments so a commented-out class cannot trigger a
        # false duplicate, and so example snippets do not count as code.
        code = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)

        for match in DECL_PATTERN.finditer(code):
            kind, name = match.group(1), match.group(2)
            key = (package, name)
            if key in declared and declared[key] != path:
                fail(
                    f"duplicate declaration: {kind} {name} in package {package}\n"
                    f"    {relative(declared[key])}\n"
                    f"    {relative(path)}"
                )
            declared.setdefault(key, path)


def check_vendored_engine() -> None:
    """The linked native engine must be exactly the engine that was published."""
    for relative_path, (expected_size, expected_digest) in VENDORED_ENGINE.items():
        path = os.path.join(REPO_ROOT, relative_path)
        if not os.path.isfile(path):
            fail(
                f"{relative_path}: the vendored Cactus engine is missing; "
                "the app cannot run Needle 3 without it"
            )
            continue

        actual_size = os.path.getsize(path)
        if actual_size != expected_size:
            fail(
                f"{relative_path}: size is {actual_size} bytes, expected {expected_size} "
                "(truncated or incomplete download)"
            )
            continue

        digest = hashlib.sha256()
        with open(path, "rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
        actual_digest = digest.hexdigest()
        if actual_digest != expected_digest:
            fail(
                f"{relative_path}: sha-256 is {actual_digest}, expected {expected_digest} "
                "(the vendored engine is not the published one)"
            )


def check_traceability() -> None:
    path = os.path.join(REPO_ROOT, "SPEC_TRACEABILITY.md")
    if not os.path.isfile(path):
        fail("SPEC_TRACEABILITY.md is missing - §63 requires every spec section to be traceable")
        return

    with open(path, encoding="utf-8") as handle:
        text = handle.read()

    covered = {int(n) for n in re.findall(r"^\|\s*(\d{1,2})\s*\|", text, re.MULTILINE)}
    missing = [n for n in range(1, 65) if n not in covered]
    if missing:
        fail(
            "SPEC_TRACEABILITY.md does not cover spec section(s): "
            + ", ".join(f"§{n}" for n in missing)
        )


def check_finalized_contracts() -> None:
    """The registry of locked decisions must still point at real guard specs."""
    path = os.path.join(REPO_ROOT, "FINALIZED_CONTRACTS.md")
    if not os.path.isfile(path):
        fail(
            "FINALIZED_CONTRACTS.md is missing - finalized behaviour would be free to "
            "regress without its registry"
        )
        return

    with open(path, encoding="utf-8") as handle:
        text = handle.read()

    ids = re.findall(r"^\|\s*(C-\d{2})\s*\|", text, re.MULTILINE)
    if len(ids) < 10:
        fail(
            f"FINALIZED_CONTRACTS.md lists only {len(ids)} contracts; a finalized decision "
            "may be replaced deliberately, never deleted to make a build pass"
        )

    specs_root = os.path.join(REPO_ROOT, "app", "src", "test", "java")
    known: set[str] = set()
    for _root, _dirs, files in os.walk(specs_root):
        known.update(files)

    for reference in sorted(set(re.findall(r"`([^`]+\.kt)`", text))):
        # Prose globs such as `*BddSpec.kt` name a convention, not one file.
        if "*" in reference:
            continue
        if os.path.basename(reference) not in known:
            fail(
                f"FINALIZED_CONTRACTS.md names the guard spec '{reference}', which does not "
                "exist under app/src/test/java (deleted or renamed? update the registry and "
                "the test together)"
            )


def main() -> int:
    use_staged = "--staged" in sys.argv
    paths = staged_files() if use_staged else walk_source_files()
    paths = [p for p in paths if os.path.isfile(p)]

    check_conflict_markers(paths)
    check_backup_files(paths)
    check_kotlin_files(paths)
    check_vendored_engine()
    check_finalized_contracts()
    if not use_staged:
        check_traceability()

    if problems:
        print("Integrity check FAILED:\n")
        for problem in problems:
            print(f"  - {problem}")
        print("\nFix these before committing. Nothing was changed by this check.")
        return 1

    scope = "staged files" if use_staged else "repository"
    print(f"Integrity check passed ({scope}, {len(paths)} files inspected).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
