# Pull Request

<!--
This template is enforced. `app/src/test/java/com/aistudio/alphapulse/build/FinalizedContractGuardBddSpec.kt`
fails the build when the required checklist items below are deleted, and
`scripts/check_integrity.py` re-checks the same markers. Removing an item is a
regression, not a cleanup.
-->

## What changed, and why

<!-- The behaviour change first; the files are the consequence. Link the issue if there is one. -->

## BDD checklist (required)

- [ ] A `*BddSpec.kt` scenario was added or updated **first**, written as *given … when … then …*, and it fails without this change.
- [ ] Existing scenarios in the areas I touched still pass unchanged — or the change to them is explained below.
- [ ] `FINALIZED_CONTRACTS.md` rows touched: <!-- C-01 … C-16, or "none" -->
- [ ] If a finalized decision changes: its registry row **and** its guard scenario are updated in this same commit, and the reason is stated below.
- [ ] I have not deleted a guard spec, weakened an assertion, or skipped a test to make this pass.

## Local gate results (paste the real output, not "passes")

- [ ] `./gradlew :app:testDebugUnitTest` — tests / failures / skipped:
- [ ] `python3 scripts/check_integrity.py` — result:
- [ ] `./gradlew :app:assembleDebug` — result:
- [ ] `./gradlew :app:verifyPackagedWeights` — verified APK + `needle3.cact` digest (when the weights path is touched):

## CI/CD

- [ ] No CI gate was weakened, disabled, made advisory, or removed (`test`, `integrity`, `build`, `model-artifacts`, `release`).
- [ ] The release job still refuses to publish artifacts whose gates did not succeed.
