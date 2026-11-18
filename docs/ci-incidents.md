# CI/CD incidents

A record of what failed in the pipeline, why it got past the gates, and the check
that now catches it first. Read this before changing `.github/workflows/`, the
release job, or any step that decides whether an APK exists.

The registry row for the guarantees below is **C-28** in
[`FINALIZED_CONTRACTS.md`](../FINALIZED_CONTRACTS.md). The checks are
`scripts/check_ci_preflight.py` (runs first, no Gradle) and
`app/src/test/java/com/aistudio/alphapulse/build/CiGateIntegrityBddSpec.kt`
(runs with the suite).

---

## v1.0.20 — 2026-11-18 — every gate green, no release

Commit `9f72f794eddd6afcacc0a16d78081ce9366464bd`, run
[44386061928](https://github.com/Hakim3691/AlphaPulse/actions/runs/44386061928).

| Job | Result |
| :--- | :--- |
| Repository Integrity | success |
| Unit Tests (API 33) | success |
| Unit Tests (API 34) | success |
| Model Artifact Sources | success (both ONNX graphs executed) |
| Assemble APK | success — but its **"Upload release APK" step was skipped** |
| Publish GitHub Release | **failure** |

The release job failed at *Require both arm64-v8a APKs before publishing*:

```
::error::The release is missing app-arm64-v8a-release.apk. A release must attach both the debug and the release APK.
```

The completeness gate did its job: it refused to publish a one-asset release.
Everything upstream, however, had reported success.

### Root cause — `assembleRelease` died inside R8, quietly

`app-arm64-v8a-release.apk` never existed. The build step ran

```bash
./gradlew :app:assembleDebug :app:assembleRelease --stacktrace 2>&1 | tee build-output.log
```

inside a `bash -e` step. Without `set -o pipefail`, a pipeline reports the exit
status of its **last** command, which is `tee` — so Gradle's failure was thrown
away and the step went green. Reproduced locally on the same commit:

```
> Task :app:minifyReleaseWithR8 FAILED
ERROR: Missing classes detected while running R8. Please add the missing classes or
apply additional keep rules that are generated in
app/build/outputs/mapping/release/missing_rules.txt.
ERROR: R8: Missing class org.bouncycastle.jsse.BCSSLParameters (referenced from: void
okhttp3.internal.platform.BouncyCastlePlatform.configureTlsExtensions(...) ...)
Missing class org.conscrypt.Conscrypt (referenced from: boolean
okhttp3.internal.platform.ConscryptPlatform$Companion.atLeastVersion(int, int, int) ...)
Missing class org.openjsse.net.ssl.OpenJSSE (referenced from: void
okhttp3.internal.platform.OpenJSSEPlatform.<init>())
```

OkHttp names three optional TLS providers it does not ship. R8 full mode (C-27,
`isMinifyEnabled = true`) treats a missing class as an error rather than a
warning, so the release variant could not be minified. Nothing before the APK
job had ever compiled the release variant or run R8, so the most expensive job
was the first to find out.

**Fix:** `app/proguard-rules.pro` now has

```proguard
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
```

(they are optional at runtime — OkHttp falls back to the platform TLS stack — so a
warning is correct and a keep rule is not). The exact rules are the ones AGP
generated in `missing_rules.txt`. `:app:minifyReleaseWithR8` now succeeds and the
release APK is produced and shrunk.

### Why the absence stayed invisible for two steps

1. **`tee` without `pipefail`** turned the failed variant into a green step. The
   workflow already documented this hazard for the *unit-test* step (a red suite
   once shipped as a passing gate) but the build step still had it.
2. **`if: hashFiles('…/app-arm64-v8a-release.apk') != ''`** on the upload step
   turned a missing APK into a **skipped** step. `if-no-files-found: error` was
   set, but the condition meant the step never ran, so it could not fail.
3. The Actions-cache stash (`release-apks-<sha>-<attempt>`, 67 MB) saved only the
   debug APK, so the release job's second transport was missing the same file.
   The completeness gate was the first thing to say so — in a different job,
   after another runner had been paid for.

### Checks added so this class cannot repeat

- **`set -o pipefail`** in the APK build step (and every other blocking step that
  pipes Gradle into `tee`). Advisory steps marked `continue-on-error: true` are
  allowed to swallow their status and are exempt.
- **The build step proves its own artifacts.** After the build it now asserts
  every APK that the requested `BUILD_TYPE` implies exists, and fails with
  `::error::BUILD_TYPE=… but … was not produced` in the same job that built it.
- **Uploads cannot self-skip.** The release APK upload is gated on the requested
  build type, not on `hashFiles` of its own file, and keeps
  `if-no-files-found: error`.
- **A `preflight` job runs the cheap gates first:** the workflow contract, both
  Kotlin source sets (`compileDebugKotlin`, `compileDebugAndroidTestKotlin`) and
  `:app:minifyReleaseWithR8`. `build` and `release` depend on it, so a compilation
  or R8 failure costs a minute instead of a full test matrix plus an NDK build.
- **`scripts/check_ci_preflight.py`** enforces all of the above statically in a
  few seconds, including that the newest `CHANGELOG.md` section (the release body)
  is not empty.
- **`CiGateIntegrityBddSpec`** asserts the same properties from Kotlin, so a
  workflow edit that reintroduces the shape fails `:app:testDebugUnitTest`.

---

## v1.0.18 — 2026-11-18 — the app did not compile

Tag `v1.0.18` (`05fab5e`), runs
[44295188302](https://github.com/Hakim3691/AlphaPulse/actions/runs/44295188302)
(push) and [44299727137](https://github.com/Hakim3691/AlphaPulse/actions/runs/44299727137)
(manual re-run). Both failed before or during the test legs; the build job never
ran, so no artifact existed to release.

The module did not compile: `AlphaPulseViewModel.exportPatternLedgerCsv()` read
`.value` from a plain `Flow`, used a C-style ternary, and the UI file had missing
imports (`Icons`, `horizontalScroll`, `TextOverflow`). Those are compiler errors —
they were simply not caught until CI compiled the sources.

**Now caught first by:** the preflight job's *Compile both Kotlin source sets*
step, which runs `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`
before any test matrix or APK work. Locally, the same check is part of the
contributor gates (`./gradlew :app:testDebugUnitTest` compiles them too).

---

## v1.0.19 — 2026-11-18 — a test leg and the model-artifact contract

Tag `v1.0.19` (`280198a`), run
[44311541154](https://github.com/Hakim3691/AlphaPulse/actions/runs/44311541154).

| Job | Result |
| :--- | :--- |
| Repository Integrity | success |
| Unit Tests (API 34) | **failure** at *Execute Unit & Robolectric Tests* |
| Unit Tests (API 33) | cancelled (fail-fast matrix) |
| Model Artifact Sources | **failure** at *Verify pinned artifact sources* |
| Assemble APK | skipped |
| Publish GitHub Release | failure at *Refuse to publish unverified artifacts* |

That run was a fix for the artifact-contract tests reading `System.getenv()`
while CI passed `-D` JVM system properties, so the two model-artifact tests
skipped instead of running (which is why "a skipped parity case still exits 0"
is itself asserted in the workflow). The test leg and the contract leg still
failed, the build was skipped, and the release guard correctly refused to publish.

**Now caught first by:** the preflight job (compilation and R8 run first, so a
broken tree fails in minutes) and the unchanged rule that a tag publishes only
when integrity, preflight, build and the model-artifact contract all succeed.

---

## v1.0.18 (`4039b28`) — the integrity gate, not the build

Commit `4039b28`, run
[44288682849](https://github.com/Hakim3691/AlphaPulse/actions/runs/44288682849).

| Job | Result |
| :--- | :--- |
| Repository Integrity | **failure** in seconds |
| Unit Tests (API 34 / API 33) | **failure** in both legs |
| Model Artifact Sources | **failure** at *Verify pinned artifact sources* |
| Assemble APK | skipped |
| Publish GitHub Release | failure at the release guard |

This is the earliest failure of the group and it has the same shape as the two
after it: the tree did not satisfy the repository's own contract, so every leg
that reads the tree failed, the build was skipped for lack of success, and the
release guard refused to publish an artifact that was never produced. The lesson
is the one the preflight job encodes: run the cheap, tree-reading gates first so
the failure is attributed in a minute rather than after a full matrix.

**Now caught first by:** `scripts/check_integrity.py` in the integrity job and in
preflight, which cross-checks the contract table, guard specs, pinned digests and
required CI steps before compiling anything.

---

## `a00c0aa` — the failure that passed on `main`

Commit `a00c0aa` ran twice with opposite results:
[44247094287](https://github.com/Hakim3691/AlphaPulse/actions/runs/44247094287) on
`main` **succeeded**, and
[44247109010](https://github.com/Hakim3691/AlphaPulse/actions/runs/44247109010) on
the `v1.0.18` branch **failed**. Both runs hit the same R8 failure on OkHttp's
optional TLS providers; only the branch run reported it.

Why the difference: the build step piped Gradle into `tee` without `pipefail`, so
the step's exit status came from `tee` and the failing `:app:minifyReleaseWithR8`
looked successful. On `main` that was invisible because branch pushes publish
nothing, so the missing release APK was never needed and the release-APK upload
self-skipped via `hashFiles`. On the tag run the release job expected the APK and
failed.

**Now caught first by:** three separate changes — `pipefail` on the build step, a
post-build assertion that every requested variant exists, an upload that cannot
self-skip (`if-no-files-found: error`, gated on the requested build type), and a
preflight `:app:minifyReleaseWithR8` pass with a throwaway keystore. The R8 cause
itself is fixed in `app/proguard-rules.pro` (`-dontwarn` for the optional TLS
providers; they are not on the runtime classpath, so nothing needs keeping).

---

## What a healthy tag run looks like

`integrity → preflight → test (API 33/34) → build → model-artifacts → release`,
where **release** requires:

- integrity, preflight, build and model-artifacts all `success`;
- both `app-arm64-v8a-debug.apk` and `app-arm64-v8a-release.apk` present, from
  either transport (artifact download or the Actions cache);
- a non-empty newest `CHANGELOG.md` section as the release body.

If a release is missing an asset, read the **build** job first: the release job
only reports the consequence.

---

## Data budget, proven offline — 2026-11-18

The device reported ~300 MB in an hour. The cause was the ten-second evaluation
cycle re-downloading data that had not changed: 1m klines, a 240-minute
liquidation heatmap, an `aggTrades?limit=500` tape the WebSocket already carried,
and a REST depth book the stream also carried. The fix is C-30 — `FetchThrottle`,
the dropped aggTrades call and stream-gated depth — but a fix with no measurement
can regress silently, so the hourly budget is now an executable test.

### Mechanism

`CycleNetworkBudgetBddSpec` walks **one simulated hour** of the real
`AlphaPulseRepository.executeAssetEvaluation` — 360 cycles at the ten-second
period — through the real caches and throttle. Three pieces make that possible
without a device or a network:

1. **`CycleNetworkBudget`** (`data/network/`) holds the intervals and payload
   assumptions the cycle uses, and derives the expected hourly counts from them:
   `1 + floor(3590 s / TTL)` fetches for a cached source, one request per horizon
   interval for the §73 pattern series. The repository consumes these constants,
   so the test and the code cannot disagree about a TTL.
2. **The repository's `clock` seam** (`() -> Long`, wall clock in production)
   lets the test advance 10 s per cycle; without it every TTL would still look
   fresh inside a fast test and an hour could never be simulated.
3. **`CountingClient`**, a `ResilientBinanceClient` whose exchange is JSON
   literals, records the path of every request; `BookedStreamManager` presents the
   book a live socket would have provided, `SilentStreamManager` presents none.

The test asserts the measured counts against a literal published table, asserts
that table against the derived constants (so shortening a TTL fails the build),
fails on any duplicate endpoint inside one cycle, fails if `aggTrades` returns
over REST, and bounds the estimated payload.

### The budget

| Source | Interval / TTL | Requests per hour | Bytes/request (assumed) |
| :--- | :--- | ---: | ---: |
| `ticker/24hr` | every cycle (10 s) | 360 | 600 B |
| `klines` 1m, limit 40 | 60 s reuse | 60 | 9,000 B |
| liquidation heatmap | 5 min reuse | 12 | 35,000 B |
| strategy `klines` 1h, limit 500 | 60 min reuse | 1 | 110,000 B |
| pattern `klines` (5 horizons) | once per horizon interval (1m…1h) | 79 | 13,000 B |
| `depth` limit 50 | only with no streamed book | 0 / 360 | 6,000 B |
| HTTP overhead | every request | — | 300 B |

Measured, one simulated hour:

- **With the streamed book: 512 requests, ≈2.35 MiB REST.**
- **Depth fallback (no WebSocket): 872 requests, ≈4.52 MiB REST.**

Published ceilings: 520 requests / 4.0 MiB with a stream, 900 requests / 8.0 MiB
without. The ceilings sit just above the measurement so a regression fails while
ordinary formatting changes do not.

### What this does and does not prove

- **Proven offline:** the REST request count and payload one hour of cycles
  issues through the real caching code, on the exact intervals the code uses.
- **Not measured here:** the WebSocket streams (`depth20@250ms`, `aggTrade`,
  `forceOrder`, `openInterest`). Their volume is socket traffic and depends on
  market activity, so modelling it would mean inventing numbers; it is outside
  this budget. The figures above are the REST floor, not the device's total.
- **Not measured on-device:** there is no phone attached to this workspace, and
  `fapi.binance.com` geo-blocks this VM, so `Data usage` in Android settings
  remains the only way to observe the total. Re-read it after installing a build
  that contains C-30: the REST component should be single-digit MiB per hour.

A failure means one of: a TTL was shortened or removed, the depth REST call lost
its stream gate, the `aggTrades` tape came back, a second series was added to the
cycle, or the payload assumptions changed. The assertion message names the bucket.
