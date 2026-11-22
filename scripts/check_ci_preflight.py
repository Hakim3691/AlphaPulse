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
7. The Robolectric `android-all` runtime cache still exists in the test job under
   the key `scripts/robolectric_runtime.json` records, that key still names the
   pinned Robolectric version and every SDK it covers, and every SDK the suite
   pins with `@Config` (or inherits from `targetSdk`) has a prefetched artifact.

Usage
-----
    python3 scripts/check_ci_preflight.py

Exit code 0 means the pipeline's own contract holds; 1 prints what to fix.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github/workflows/build-apk.yml"
# Robolectric fetches android-all-instrumented from Maven Central while the tests
# run, so its jars are outside Gradle's and setup-gradle's caches. The registry
# below is shared with RobolectricRuntimeCacheBddSpec, so the workflow key, the
# prefetched artifacts and the SDKs the suite pins cannot drift apart.
ROBOLECTRIC_REGISTRY = ROOT / "scripts/robolectric_runtime.json"
VERSION_CATALOG = ROOT / "gradle/libs.versions.toml"
APP_BUILD = ROOT / "app/build.gradle.kts"
TEST_SOURCES = ROOT / "app/src/test"
ROBOLECTRIC_M2 = "~/.m2/repository/org/robolectric"
ROBOLECTRIC_CACHE_ID = "robolectric-cache"

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


def check_robolectric_runtime_cache(text: str) -> None:
    """The android-all jars are fetched mid-test, so the cache cannot be lost.

    Robolectric resolves `android-all-instrumented` at test time; that is why a
    429 from Maven Central once failed the API 34 leg after every Gradle
    dependency had resolved. The test job caches `~/.m2/repository/org/robolectric`
    under a key derived from the pinned Robolectric version and the SDKs the
    suite runs on, and prefetches those jars only when the cache missed. Everything
    here is shared with `RobolectricRuntimeCacheBddSpec` through
    `scripts/robolectric_runtime.json`, so a lost step, a changed key or an SDK
    added without its artifact fails in seconds instead of on a cold runner.
    """
    if not ROBOLECTRIC_REGISTRY.is_file():
        fail(
            "scripts/robolectric_runtime.json is missing; it is the shared source of "
            "truth for the Robolectric android-all cache key and the SDKs it covers"
        )
        return
    try:
        registry = json.loads(ROBOLECTRIC_REGISTRY.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        fail(f"scripts/robolectric_runtime.json is not valid JSON: {error}")
        return

    version = registry.get("robolectric_version")
    cache_key = registry.get("cache_key")
    sdk_artifacts = registry.get("sdk_artifacts")
    if not isinstance(version, str) or not version:
        fail("scripts/robolectric_runtime.json does not record `robolectric_version`")
        return
    if not isinstance(cache_key, str) or not cache_key:
        fail("scripts/robolectric_runtime.json does not record `cache_key`")
        return
    if not isinstance(sdk_artifacts, dict) or not sdk_artifacts:
        fail(
            "scripts/robolectric_runtime.json records no `sdk_artifacts`, so nothing "
            "documents which SDKs the cache covers"
        )
        return
    if not all(
        isinstance(sdk, str) and sdk.isdigit() and isinstance(artifact, str) and artifact
        for sdk, artifact in sdk_artifacts.items()
    ):
        fail("scripts/robolectric_runtime.json maps SDKs to artifacts with a non-numeric key")
        return

    # The key must be derived from what the registry documents. If it is not, a
    # version or SDK bump restores jars for the old set, the new one is downloaded
    # mid-test, and the flake this cache exists to remove is back.
    sdk_suffix = "-".join(sorted(sdk_artifacts, key=int))
    derived = re.compile(
        rf"^robolectric-android-all-{re.escape(version)}-sdk{re.escape(sdk_suffix)}-v\d+$"
    )
    if not derived.match(cache_key):
        fail(
            f"the Robolectric cache key {cache_key!r} does not name version {version} and "
            f"its covered SDKs (sdk{sdk_suffix}); a version or SDK bump must change the key, "
            "otherwise a cold runner restores the old jars and downloads the new one mid-test"
        )

    if VERSION_CATALOG.is_file():
        pinned = re.search(
            r'(?m)^robolectric\s*=\s*"([^"]+)"',
            VERSION_CATALOG.read_text(encoding="utf-8"),
        )
        if pinned is None:
            fail("gradle/libs.versions.toml no longer pins Robolectric")
        elif pinned.group(1) != version:
            fail(
                f"Robolectric is pinned at {pinned.group(1)} in gradle/libs.versions.toml but "
                f"the runtime registry documents {version}; Robolectric names its android-all "
                "jars per version, so both and the cache key must move together"
            )

    test_job = job(text, "test:")
    if not test_job:
        fail("the workflow has no `test` job to check for the Robolectric runtime cache")
        return

    cache_steps = [
        (name, block)
        for name, block in steps(test_job)
        if ROBOLECTRIC_M2 in block and "actions/cache" in block
    ]
    if not cache_steps:
        fail(
            f"the test job no longer caches {ROBOLECTRIC_M2}; Robolectric fetches its "
            "android-all jars during the run, so a cold runner downloads them again and a "
            "429 fails the leg after every Gradle dependency has resolved"
        )
    else:
        name, block = cache_steps[0]
        if f"key: {cache_key}" not in block:
            fail(
                f"the Robolectric cache step '{name}' does not use the key "
                f"{cache_key!r} that scripts/robolectric_runtime.json documents"
            )
        if f"id: {ROBOLECTRIC_CACHE_ID}" not in block:
            fail(
                f"the Robolectric cache step '{name}' lost `id: {ROBOLECTRIC_CACHE_ID}`, so "
                "the cold-cache prefetch can no longer tell a hit from a miss"
            )

    prefetch_steps = [
        (name, block)
        for name, block in steps(test_job)
        if name.startswith("Prefetch the Robolectric Android runtime jars")
    ]
    if not prefetch_steps:
        fail(
            "the test job lost its cold-cache Robolectric prefetch, so a first run races "
            "Robolectric's own fetcher with no bounded retry"
        )
    else:
        name, block = prefetch_steps[0]
        if f"steps.{ROBOLECTRIC_CACHE_ID}.outputs.cache-hit != 'true'" not in block:
            fail(
                f"'{name}' is no longer gated on a cache miss, so it pays the download on "
                "every run and the cache stops being the fix"
            )
        for sdk, artifact in sdk_artifacts.items():
            if f"fetch {artifact}" not in block:
                fail(
                    f"SDK {sdk} is documented as cached, but '{name}' never fetches "
                    f"{artifact}, so a cold runner leans on Robolectric's own download"
                )
        # Maven names the files after the artifactId, not after the version
        # directory. Writing <version>.jar made every prefetch URL 404, so the step
        # only warned and the cold runner still downloaded the jars itself.
        if "android-all-instrumented-$artifact" not in block:
            fail(
                f"'{name}' does not build Maven's android-all-instrumented-<artifact> file "
                "name, so its URLs 404 and the cold-cache backstop prefetches nothing"
            )

    cache_at = test_job.find(f"id: {ROBOLECTRIC_CACHE_ID}")
    tests_at = test_job.find("Execute Unit & Robolectric Tests")
    if cache_at < 0 or tests_at < 0 or cache_at > tests_at:
        fail(
            "the Robolectric runtime cache must be restored before 'Execute Unit & "
            "Robolectric Tests' runs, or it caches nothing the test run can use"
        )

    used = pinned_robolectric_sdks()
    uncovered = sorted(used - set(sdk_artifacts), key=int)
    if uncovered:
        fail(
            f"the suite runs Robolectric on SDK {uncovered}, but "
            "scripts/robolectric_runtime.json has no android-all artifact for it. Add the "
            "artifact, the cold-cache prefetch entry and a new cache key in the same change; "
            "otherwise the first runner that reaches that SDK downloads it mid-test"
        )


def pinned_robolectric_sdks() -> set[str]:
    """Every SDK the suite asks Robolectric to run on.

    `@Config(sdk = ...)` wins; a spec that pins nothing inherits `targetSdk` from
    `app/build.gradle.kts`, so that SDK is covered whether or not a test names it.
    """
    sdks: set[str] = set()
    if TEST_SOURCES.is_dir():
        for path in TEST_SOURCES.rglob("*.kt"):
            text = path.read_text(encoding="utf-8")
            for args in re.findall(r"@Config\((.*?)\)", text, re.S):
                for block in re.findall(r"sdk\s*=\s*\[([^\]]*)\]", args):
                    sdks.update(re.findall(r"\d+", block))
                for scalar in re.findall(r"sdk\s*=\s*(\d+)", args):
                    sdks.add(scalar)
    if APP_BUILD.is_file():
        target = re.search(
            r"(?m)^\s*targetSdk\s*=\s*(\d+)", APP_BUILD.read_text(encoding="utf-8")
        )
        if target:
            sdks.add(target.group(1))
    return sdks


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
    check_robolectric_runtime_cache(text)
    check_changelog()

    if problems:
        print("Preflight FAILED:\n")
        for problem in problems:
            print(f"  - {problem}")
        print("\nFix the pipeline before paying for the test matrix and the APK build.")
        return 1

    print(
        "Preflight check passed (workflow contract, release completeness, Robolectric "
        "runtime cache, changelog body)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
