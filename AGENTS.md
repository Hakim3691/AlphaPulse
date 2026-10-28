# AGENTS.md — standing instructions for every contributor and coding agent

This file is the operating contract for work in this repository. It exists so the
process does not have to be restated in every request. Read it before changing
code; read [`FINALIZED_CONTRACTS.md`](FINALIZED_CONTRACTS.md) before changing
behaviour that is already decided.

## The process (in this order, every time)

1. **BDD scenario first.** Add or extend a `*BddSpec.kt` under
   `app/src/test/java/com/aistudio/alphapulse/` written as
   *given … when … then …*. Run it and watch it fail for the right reason before
   writing the implementation. A bug fix gets a scenario that reproduces the bug.
2. **Implement minimally.** No unrelated refactors, no new abstractions, no UI or
   schema changes the scenario does not justify.
3. **Run the local gates** and read the real output:
   - `./gradlew :app:testDebugUnitTest`
   - `python3 scripts/check_integrity.py`
   - `./gradlew :app:assembleDebug`
   - `./gradlew :app:verifyPackagedWeights` (any change to the weights, build or
     packaging path)
4. **CI/CD ships it.** Push to `main`: integrity → unit/Robolectric tests (API 33
   and 34) → APK assembly with packaged-weights verification → published
   model-artifact contract → GitHub Release from the verified artifacts. A pushed
   `v*` tag publishes a versioned release. Do not bypass, weaken or skip a gate to
   make a red run green — fix the cause.

## Finalized decisions are not up for silent reversal

`FINALIZED_CONTRACTS.md` records sixteen decisions (C-01 … C-16) with the guard
spec that proves each one. `FinalizedContractGuardBddSpec` fails the build when a
contract row disappears, a guard spec is missing, a pinned digest drifts, a removed
component returns, or a documented CI gate stops running. `scripts/check_integrity.py`
repeats that cross-check for CI's integrity job and the pre-commit hook.

Changing a finalized decision is allowed only as a deliberate commit that:

- updates the guard spec's *given/when/then* scenarios to the new behaviour;
- updates the matching row in `FINALIZED_CONTRACTS.md`, including pinned values;
- states in the commit body **why** the earlier decision is being replaced.

Deleting a row, deleting a guard spec, or relaxing an assertion is a regression.

## Non-negotiables

- Decision routing is on-device: Cactus Needle 3 plus the deterministic fallback.
  No Microsoft-Decision-1, no routing endpoint, no API key, no remote banner.
- The Cactus engine is compiled in, never downloaded; the Needle 3 checkpoint is
  bundled and pinned (35,335,380 bytes, sha-256
  `c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38`), mounted
  from the APK and verified before use.
- Only `arm64-v8a` is built and shipped; debug and release APKs are separate CI
  artifacts.
- Signing uses the persistent cache-backed keystore and `versionCode` grows
  monotonically, so updates install in place without wiping user data or models.
- Every evaluation writes exactly one ledger row with its verdict, calibrated
  confidence, answering router and horizon; the UI's history flow reads that table.
- Ask for a user decision instead of guessing when the correct behaviour is
  genuinely ambiguous.

## Paperwork that is already covered

Do not restate the process in a pull request description beyond the template:
`.github/pull_request_template.md` asks for exactly the checklist (BDD scenario,
registry rows, local gate output, no weakened CI). Both files are pinned by
`FinalizedContractGuardBddSpec` and `scripts/check_integrity.py`.
