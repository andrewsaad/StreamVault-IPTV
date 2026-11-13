# HANDOFF: where we left off (2026-11-09)

Branch of record `feat/channel-health` (tip 82fa1b94 plus this close-out). Older handoffs: `node ~/.claude/scripts/doc-lookup.js HANDOFF_FULL.jsonl <date>`.

## Done this session (2026-11-08 to 11-09)
- EPG presets review fixes F1-F22 (F18 reverted as won't-fix), F13 one match pass per provider, R1-R4, SC-008 descoped; `feat/epg-presets` pushed to the fork (2163a91a).
- Refresh-rate double switch on zap: root cause found by log trace on the game room TV and fixed (`stop()` no longer clears the vote, grace 15 s); verified by log on the device.
- Sports tab: later rebroadcasts of a match are dropped (4-15 h after the first ended); not yet seen on a screen.
- W2, W3 fixed; W33, W4 fixed and hardened after review (plain Watch Next extras accept internal xtream://, Stalker URLs only).
- In-app Channel clean-up built and device-tested on the game room TV (entry, scan, stop, resume after the TV slept): scanner with retry and a repeated-503 rule, hide through the hidden-channels list, give-up message, Settings search entry, cleanup on provider delete, 38 locales.
- Settings strings rounds 1-2, mojibake repair in 7 locales + guard test, log sanitizer (30 Log.w sites), required text lambdas.
- Findings: FINDINGS.md (index) + FINDINGS_FULL.jsonl. Evidence on the guide: two-hour offset between guide families, matches on screen missing from the guide, beIN English channels return 503, French Sport 3 and Max 4-10 have no guide.

## Decisions (Andrew)
Findings go in the repo (Issues disabled on the fork); push to the fork only, no PR; defer the iPhone app; translations accepted unreviewed (note for release); fix guide scroll jank (SC-007 about 7% vs 6%) starting with W1; no presets-off baseline (would delete 27 manual beIN mappings); stale Chrome may be stopped before builds.

## Blockers
- PC memory: Windows kernel paged pool 5.7 GB made the harness stop about ten Gradle runs. Andrew is restarting the PC. After it, check `Pool Paged Bytes` (< 2 GB); if it climbs again look for a leaking driver.
- Gate on tip 108879da (2026-11-13, desktop): `:data` 2,233 tests 0 failed; `:feature:settings` and `:domain:test` BUILD SUCCESSFUL; `:app` tests 224 passed and compiled (2026-11-12, older tip). Not run on this tip: lint and `:app:assembleDebug` (step C hung at `:data:test` for 11 h and was killed). `:player` `IdleConnectionResetRecoveryTest` (resume offset 532,480 vs 1,000,000) also fails on fork `master` 68b2ca4a, so it is not from this branch.

## Next priorities, in order
1. Restart the keep-awake loop for the game room TV (192.168.68.150:38189): scratchpad `keepalive_gameroom.ps1`, or send a wake key every 45 s. The in-app scan stalls when it sleeps.
2. Gate in three steps with `gradlew --stop` between (`/c/tmp/sv_gradle.sh`): A `:data:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin`; B `:feature:settings:testDebugUnitTest :domain:test`; C remaining modules, `lintDebug`, `:app:assembleDebug`. Lint always shows the same 63 pre-existing errors. Fix what fails.
3. Independent reviewer pass on the three unverified fixes; then reinstall the debug app on the game room TV (muted by `appops PLAY_AUDIO deny`, debug app only) and re-check the dialog, give-up line, search entry and scanner retry.
4. #50 guide check: preview about 30 sports channels at a European kickoff, decide which guide file is right, fix matching/time shift and give French channels the French rows, with tests.
5. #54 scroll fix: W1 first (`LiveGuideGrid.kt:73-78`), re-measure with `/c/tmp/sv_jank.sh`.
6. Push `feat/channel-health` and update `feat/epg-presets` on the fork once the gate passes.

## Loose ends
- In-app scan reached channel id 736 of 5,537 with 5 stored failures (two-failed-days rule: review suggestions after a second day). PC scan finished 2026-11-11 (5,534 channels: OK 3212, EMPTY 1543, UNCLEAR 747, DEAD 32). The 2026-11-12 re-probe is NOT usable: it rescanned all channels while Gradle ran on the desktop, 1,548 answers were network errors, 487 first-scan OK became EMPTY and 178 EMPTY became OK, so EMPTY looks like a provider artifact. Hide nothing from either scan; redo a clean re-probe on an idle desktop (one-account rule). Delete `secret.md` (gitignored, holds the provider login) when the scans are done.
- Open tasks: #30, #33 (Fire TV), #31, #32, #38, #36 (deferred), #49-#54.
- Update FINDINGS.md statuses for W4/W33 after the gate.
- Stale worktrees under `.claude/worktrees/` and `C:\dev\sv-*` are not cleaned up (needs Andrew's OK; some hold unmerged branches).
- The Settings press route for SC-004 and the beIN preset timing (F13) are unmeasured; both need the manual-mappings risk resolved.
