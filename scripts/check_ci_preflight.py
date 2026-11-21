#!/usr/bin/env python3
"""
Preflight checks for the CI/CD pipeline itself.

Why this exists
---------------
The v1.0.20 tag run (`9f72f79`) looked like a success and produced no release:
`assembleRelease` failed inside `:app:minifyReleaseWithR8`, but the build step ran
`gradle ... | tee build-output.log` without `pipefail`, so the step reported the
status of `tee`. The release APK upload was gated on `hashFiles(...)`, so the
missing file became a *skipped* step, and the first visible error arrived in the
release job - a different machine, five minutes later, after an NDK build that
had to be paid for again.

The checks below are the same class of mistake, and they are cheap: no Gradle, no
Android SDK, no network. They run first so a pipeline that cannot succeed fails
while it is still cheap to fix.

What it checks
--------------
1. No blocking step pipes `gradlew` into `tee` without `pipefail`.
2. The APK build step asserts that every variant it asked for was produced.
3. An APK upload fails when its APK is missing (`if-no-files-found: error`) and
   never decides whether to run by looking for its own file (`hashFiles`).
4. A `preflight` job exists, runs this script plus both Kotlin compilations and
   the release R8 pass, and the `build`/`release` jobs depend on it.
5. The release job is tag-gated and still carries its completeness gate.
6. The newest CHANGELOG section - the published release body - has real content.

Usage
-----
    python3 scripts/check_ci_preflight.py

Exit code 0 means the pipeline's own contract holds; 1 prints what to fix.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github/workflows/build-apk.yml"

problems: list[str] = []


def fail(message: str) -> None:
    problems.append(message)


def steps(text: str) -> list[tuple[str, str]]:
    """Every `- name:` step in a workflow, with its body."""
    found: list[tuple[str, str]] = []
    matches = list(re.finditer(r"(?m)^      - name: (.+)$", text))
    for index, match in enumerate(matches):
        start = match.start()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        found.append((match.group(1).strip(), text[start:end]))
    return found


def job(text: str, name: str) -> str:
    """The block of a top-level job, from its `name:` line to the next job."""
    start = text.find(f"\n  {name}")
    if start < 0:
        return ""
    rest = text[start + 1:]
    # Search past the job's own header, which would otherwise match immediately.
    nxt = re.search(r"(?m)^  [a-z][a-z-]*:$", rest[1:])
    return rest if nxt is None else rest[: nxt.start() + 1]


def check_pipeline_status(text: str) -> None:
    """A blocking step may not report tee's exit status."""
    for name, block in steps(text):
        if "gradlew" not in block or "| tee" not in block:
            continue
        # Advisory steps are allowed to swallow their status: that is what
        # `continue-on-error` means, and the process gate is elsewhere.
        if "continue-on-error: true" in block:
            continue
        if "set -o pipefail" not in block and "set -euo pipefail" not in block:
            fail(
                f"step '{name}' pipes gradle into tee without `set -o pipefail`, so a "
                "failing Gradle task still reports a green step"
            )


def check_build_proves_its_artifacts(text: str) -> None:
    """The build step must assert the variants it asked for exist."""
    block = dict(steps(text)).get("Build APK Packages", "")
    if not block:
        fail("the workflow has no 'Build APK Packages' step to check")
        return
    for apk in ("app-arm64-v8a-debug.apk", "app-arm64-v8a-release.apk"):
        if apk not in block:
            fail(
                f"the build step never names {apk}, so nothing proves the requested "
                "variant was produced before the release job looks for it"
            )
    if not re.search(r'\[ ! -s "\$apk" \]', block):
        fail(
            "the build step does not fail on a missing requested APK; add the "
            "`for apk in ... [ ! -s \"$apk\" ]` assertion"
        )


def check_apk_uploads(text: str) -> None:
    """An APK upload fails on a missing file and never self-skips."""
    checked = 0
    for name, block in steps(text):
        if not name.startswith("Upload ") or "APK" not in name:
            continue
        checked += 1
        if "if-no-files-found: error" not in block:
            fail(f"'{name}' must set `if-no-files-found: error`")
        if "if-no-files-found: ignore" in block:
            fail(f"'{name}' must not ignore a missing APK")
        if "hashFiles(" in block:
            fail(
                f"'{name}' is gated on hashFiles(...), which turns a missing APK into a "
                "skipped step and a green build job"
            )
    if not checked:
        fail("the workflow has no APK upload step to check")


def check_preflight_wiring(text: str) -> None:
    """The cheap gates exist and the expensive jobs depend on them."""
    preflight = job(text, "preflight:")
    if not preflight:
        fail("the workflow has no `preflight` job for the cheap gates")
        return
    for gate in (
        "scripts/check_ci_preflight.py",
        ":app:compileDebugKotlin",
        ":app:compileDebugAndroidTestKotlin",
        ":app:minifyReleaseWithR8",
    ):
        if gate not in preflight:
            fail(f"the preflight job must run {gate}")

    build = job(text, "build:")
    if not re.search(r"needs:\s*\[[^\]]*preflight", build):
        fail("the `build` job must need `preflight`, or the cheap gate runs too late")

    release = job(text, "release:")
    if "preflight" not in release:
        fail("the `release` job must refuse to publish when `preflight` did not succeed")


def check_release_job(text: str) -> None:
    """The release is tag-only and cannot publish a half-built release."""
    release = job(text, "release:")
    if not release:
        fail("the workflow has no `release` job")
        return
    if "startsWith(github.ref, 'refs/tags/v')" not in release:
        fail("the release job must stay gated on a v* tag")
    if "Refuse to publish unverified artifacts" not in release:
        fail("the release job lost its 'Refuse to publish unverified artifacts' guard")
    if "Require every arm64-v8a APK before publishing" not in release:
        fail("the release job lost its completeness gate")


def check_universal_delivery(text: str) -> None:
    """The on-demand feature must also ship as a fused, installable universal APK."""
    block = dict(steps(text)).get("Build the universal APK from the verified bundle", "")
    if not block:
        fail(
            "the workflow has no universal APK stage, so the on-demand trader module is "
            "not installable from a sideloaded release"
        )
        return

    for required in (
        "--mode=universal",
        "app/build/outputs/bundle/debug/app-debug.aab",
        "app/build/outputs/apk/universal/app-arm64-v8a-universal-debug.apk",
        "scripts/verify_universal_apk.py",
    ):
        if required not in block:
            fail(f"the universal APK stage must contain {required!r}")

    upload = dict(steps(text)).get("Upload universal APK (arm64-v8a)", "")
    if not upload:
        fail("the workflow must upload the universal APK as its own artifact")
    elif "if-no-files-found: error" not in upload:
        fail("the universal APK upload must fail when its APK is missing")

    release = job(text, "release:")
    if "app-arm64-v8a-universal-debug.apk" not in release:
        fail("the release job must require the universal APK before publishing")


def check_changelog() -> None:
    """The newest CHANGELOG section is the published release body."""
    path = ROOT / "CHANGELOG.md"
    if not path.is_file():
        fail("CHANGELOG.md is missing; the release job reads the newest section from it")
        return
    lines = path.read_text(encoding="utf-8").splitlines()
    start = next((i for i, line in enumerate(lines) if line.startswith("## [")), None)
    if start is None:
        fail("CHANGELOG.md has no '## [' version section to publish")
        return
    end = next(
        (i for i in range(start + 1, len(lines)) if lines[i].startswith("## [")),
        len(lines),
    )
    body = [
        line for line in lines[start + 1:end]
        if line.strip() and not line.startswith("#")
    ]
    if not body:
        fail("the newest CHANGELOG section is empty, so the release would publish no notes")


def main() -> int:
    if not WORKFLOW.is_file():
        print(f"Preflight FAILED:\n  - {WORKFLOW} is missing")
        return 1
    text = WORKFLOW.read_text(encoding="utf-8")

    check_pipeline_status(text)
    check_build_proves_its_artifacts(text)
    check_apk_uploads(text)
    check_preflight_wiring(text)
    check_release_job(text)
    check_universal_delivery(text)
    check_changelog()

    if problems:
        print("Preflight FAILED:\n")
        for problem in problems:
            print(f"  - {problem}")
        print("\nFix the pipeline before paying for the test matrix and the APK build.")
        return 1

    print("Preflight check passed (workflow contract, release completeness, changelog body).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
