# Validation: release gate (T043)

Date: 2026-11-06
Branch: epg/gate (from feat/epg-presets at f54b2da6), Windows, JDK 17, Gradle with `--no-build-cache`.

## Unit tests

Command: `./gradlew :domain:test :data:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:live:testDebugUnitTest :core:ui:testDebugUnitTest :app:testDebugUnitTest --no-build-cache --continue`
Result: BUILD SUCCESSFUL (14m 58s). Counts are from the Gradle test result XML files.

| Module | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| :domain | 286 | 0 | 0 | 0 |
| :data | 2023 | 0 | 0 | 0 |
| :feature:settings | 1139 | 0 | 0 | 0 |
| :feature:live | 287 | 0 | 0 | 0 |
| :core:ui | 24 | 0 | 0 | 0 |
| :app | 210 | 0 | 0 | 0 |
| Total | 3969 | 0 | 0 | 0 |

## Lint

Run the way CI does (`.github/workflows/release.yml`): `:app:lintDebug :data:lintDebug :player:lintDebug verifyLintBaseline`.

- :player:lintDebug: no new issues (10 filtered by baseline).
- :data:lintDebug: no new issues (18 filtered by baseline).
- :app:lintDebug: no new issues (228 filtered by baseline). One error appeared in the first run, a PropertyEscape error on the unescaped `sdk.dir` in this worktree's untracked local.properties. It is a setup artifact, not a repo file. After escaping the path and re-running the analysis, lint found no new issues.
- verifyLintBaseline: passed (app 369, data 18, player 10 baseline records).
- Baseline files were not edited. Lint reported 141 stale app baseline entries (already fixed or not analyzed); not touched.

New issues versus baseline: 0.

## Build

`./gradlew :app:assembleDebug --no-build-cache`: executed and produced `app/build/outputs/apk/debug/app-debug.apk` (run together with lint in one invocation; that invocation's overall status was FAILED only because of the PropertyEscape error above).
