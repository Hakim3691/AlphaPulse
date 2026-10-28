# AlphaPulse — Finalized Contracts (Do Not Regress)

Every behaviour below was decided, implemented, tested and shipped. It is **final**
until someone deliberately re-decides it. This file exists because changes kept
undoing earlier work: Microsoft-Decision-1 was removed and then lingered in the
docs, per-ABI APKs came back after the build went arm64-only, and the weights
pipeline was re-broken by edits to the build and the workflow.

**Read this before changing routing, the model download/packaging path, the build
contract, the ledger or the calibration pipeline.**

## How a finalized contract is enforced

1. Each contract names one or more **guard specs** (`*BddSpec.kt` / `*Test.kt`
   under `app/src/test/java/com/aistudio/alphapulse/`) whose Given/When/Then
   scenarios assert it.
2. `build/FinalizedContractGuardBddSpec.kt` reads this file and fails the build if a
   contract row disappears, if a guard spec it names is missing or has no scenario,
   or if a pinned value recorded here drifts from the code.
3. `scripts/check_integrity.py` repeats the doc/guard cross-check, so the CI
   `Repository Integrity` job and the pre-commit hook catch drift even when Gradle
   is not run.
4. CI (`.github/workflows/build-apk.yml`) runs the whole suite, the integrity check,
   the packaged-weights verification and the model-artifact contract on every push,
   then publishes a GitHub Release with the arm64-v8a APK assets.

## Changing a finalized contract

Allowed only as a deliberate decision, in a single commit, that:

1. updates the guard spec's Given/When/Then scenarios to the new behaviour (red
   first, then green — never delete the scenario to make a build pass);
2. updates the row below, including any pinned value it records;
3. explains in the commit body **why** the earlier decision is being replaced;
4. fills in the checklist in [`.github/pull_request_template.md`](.github/pull_request_template.md)
   and follows [`AGENTS.md`](AGENTS.md), which restate this process for
   contributors and coding agents. Both files are pinned by the guard spec and by
   `scripts/check_integrity.py`, so deleting an item from them fails the build.

Deleting a guard spec, weakening an assertion, or reverting a row without its
scenario is a regression, and CI must fail on it.

## The registry

Pinned Needle 3 checkpoint (recorded in the row below and asserted against
`ModelArtifacts.NEEDLE3`): **35,335,380 bytes**, sha-256
`c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38`.

| ID | Finalized behaviour | Guard spec(s) | Also enforced by |
| :-- | :--- | :--- | :--- |
| C-01 | Decision routing runs on device only: Cactus Needle 3 plus the deterministic fallback rules. There is no Microsoft-Decision-1, no routing endpoint, no API key, no network timeout and no remote-model banner. Every refusal (no checkpoint, no engine, suppressed call, ungrounded argument, low confidence) ends in the deterministic rules. | `domain/decision/Needle3DecisionRouterBddSpec.kt`, `domain/agent/DecisionRouterAgentBddSpec.kt` | `DecisionSource`, `SPEC_TRACEABILITY.md` §29/§44/§45 |
| C-02 | The Cactus engine is compiled into the APK, never downloaded: `libneedle.a` for arm64/armv7 and `needle.h` are vendored under `app/src/main/cpp/engine/` with pinned sizes and digests, and CMake links them per ABI. | `domain/decision/VendoredEngineArtifactBddSpec.kt`, `domain/decision/JniNeedle3BridgeBddSpec.kt` | `scripts/check_integrity.py` (vendored digest guard) |
| C-03 | Only `arm64-v8a` is built and shipped. No `armeabi-v7a`, `x86`, `x86_64` native build and no universal APK; both `abiFilters` and the split `include(...)` list contain exactly `arm64-v8a`. | `build/BuildContractBddSpec.kt` | `app/build.gradle.kts`, CI artifact steps |
| C-04 | CI uploads the debug APK and the release APK as two separate artifacts (`alphapulse-arm64-v8a-debug`, `alphapulse-arm64-v8a-release`). They are never bundled into one archive. | `build/BuildContractBddSpec.kt` | `.github/workflows/build-apk.yml` |
| C-05 | Signing uses a persistent, cache-backed keystore (secrets `KEYSTORE_BASE64` / `DEBUG_KEYSTORE_BASE64` when configured) and `versionCode` grows monotonically with the CI run number (offset 10,000), with the `VERSION_CODE` override still honoured. In-place updates must not need an uninstall. | `build/BuildContractBddSpec.kt` | `.github/workflows/build-apk.yml` |
| C-06 | The Needle 3 weights are pre-bundled into the APK before assembly by `:app:bundleNeedle3Weights` (which re-verifies size and SHA-256), and `.cact`/`.onnx`/`.bin` stay in `noCompress` so the engine maps them instead of inflating 35 MB at runtime. | `build/BuildContractBddSpec.kt`, `data/model/BundledModelInstallerBddSpec.kt` | `app/build.gradle.kts` |
| C-07 | The bundled checkpoint is pinned to 35,335,380 bytes and sha-256 `c9d915ec…8170c38` — one pin shared by the build task, the in-APK verifier and the runtime verifier. A truncated or tampered asset is rejected, never mounted. | `data/model/BundledModelInstallerBddSpec.kt` | `:app:verifyPackagedWeights`, `ModelArtifacts.NEEDLE3` |
| C-08 | `:app:verifyPackagedWeights` opens every built APK and proves `assets/needle3.cact` is present, stored uncompressed, the pinned size and the pinned digest — and fails loudly when there is no APK or no entry. | `build/BuildContractBddSpec.kt` | `app/build.gradle.kts`, CI build job |
| C-09 | A running app reads the checkpoint through the real `AssetManager` and mounts it with `BundledModelInstaller` (verified copy into the engine's private path, no network). | `data/model/BundledWeightsAssetManagerBddSpec.kt` | `BundledModelInstaller` |
| C-10 | Every asset evaluation writes exactly one `DecisionRecordEntity` row with its verdict, calibrated confidence, answering router and horizon; the UI's `getDecisionHistoryFlow` emits it unaided; symbols never leak into each other's history; re-evaluation appends rather than replaces. Checkpoint-backed verdicts keep their `NEEDLE3` source and their own confidence instead of being downgraded. | `data/repository/AssetEvaluationDecisionRecordBddSpec.kt`, `data/db/DecisionRecordDaoBddSpec.kt` | `DecisionRecordDao`, `DecisionHistorySection` |
| C-11 | Predictions follow the §34 state machine ISSUED → SETTLED → LEARNED / EXPIRED / VOID; illegal transitions are rejected rather than guessed. | `domain/quant/PredictionStateMachineBddSpec.kt` | `PredictionStateMachine` |
| C-12 | Selecting a coin runs the 30-day, per-horizon calibration for whichever forecasters are mounted, scores MAE/RMSE/directional accuracy per horizon and persists normalised ensemble weights per coin and horizon (recalibration replaces, two coins keep their own weights); Needle 3 selects the horizon set and the deterministic plan covers every refusal. | `domain/ensemble/CoinCalibrationBddSpec.kt`, `data/repository/CoinCalibrationPersistenceBddSpec.kt` | `CoinCalibrationOrchestrator`, `AlphaPulseRepository.calibrateCoin` |
| C-13 | Room schema version 6 with the `ensemble_weights` table is reached through the production 5→6 migration; existing rows survive and weights round-trip across a reopen. | `data/db/EnsembleWeightsMigrationBddSpec.kt` | `AppDatabase` |
| C-14 | Prices are rendered with the strict 5-decimal standard everywhere (dashboard, charts, ledger, exports, notifications). | `core/FormattersTest.kt` | `Formatters` |
| C-15 | Every one of the 64 spec sections stays traceable in `SPEC_TRACEABILITY.md`. | `scripts/check_integrity.py` | CI `Repository Integrity` job |
| C-16 | No removed remote decision component returns: production sources contain no `Decision1ApiRouter`, no `Decision1Configuration` and no decision API key, and the README no longer advertises per-ABI or multi-ABI APKs. | `build/FinalizedContractGuardBddSpec.kt` | this file |

## The change process (this is the standing rule)

Every change — feature, bug fix or refactor — follows the same path, so nobody has
to be reminded:

1. **BDD first.** Write or extend a `*BddSpec.kt` scenario as
   *given … when … then …* that fails for the new requirement (or reproduces the bug).
   Keep the existing scenarios of the area you are touching.
2. **Implement minimally** until the scenario passes. If the change alters a
   finalized behaviour, edit this registry and the guard scenario in the same commit.
3. **Verify locally:** `./gradlew :app:testDebugUnitTest`,
   `python3 scripts/check_integrity.py`, `./gradlew :app:assembleDebug`, and
   `./gradlew :app:verifyPackagedWeights` when the weights path is involved.
4. **CI/CD does the rest.** A push to `main` runs integrity → unit/Robolectric tests
   (API 33 and 34) → APK assembly + packaged-weights verification → the published
   model-artifact contract, then publishes a GitHub Release from the verified
   artifacts. A `v*` tag publishes a versioned release. The release job refuses to
   publish when any of those jobs did not succeed.
