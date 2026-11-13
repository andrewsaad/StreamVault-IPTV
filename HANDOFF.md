# HANDOFF: where we left off (2026-11-13, about 11:00 PST)

Older handoffs (incl. the 2026-11-09 one with the full 11-13 loose ends): `node ~/.claude/scripts/doc-lookup.js HANDOFF_FULL.jsonl <date>`.

## Done this session
- Channel-health snapshot feature (export/import results file, "Scan new", "Recheck failed", 38 locales, 9 new strings): branch `feat/channel-health-snapshot`, tip `d3b236d4`, pushed to the fork only (no PR). The file carries no server address, login or stream URL; imports only create hints and hide nothing.
- Desktop test evidence: snapshot branch `:data` 161 tests and `:feature:settings` 31 tests, 0 failures (BUILD SUCCESSFUL 3m 10s, 10:29). Integ branch `integ/agents-round1` tip `65408d15` (test fix cherry-picked, pushed to fork): step L (4 agent test classes) BUILD SUCCESSFUL 1m 55s; step M (player + playback tests) BUILD SUCCESSFUL 1m 23s.
- Three agent-written tests fixed (wrong entity fields, `providersForcedByBackupReferences` moved to top level in `BackupManagerImpl.kt`).
- Scratchpad tooling lesson: `rdC2.ps1` was sed-derived and still read `stepC1.txt`, which produced false "same errors" readings. Read result files by their own name.

## Decisions (Andrew)
Standing OK for a one-shot read-only adb screenshot of the game-room TV, no prompt (memory updated); "just do it" = keep going without asking. Snapshot design: app only, manual buttons only. PC-vs-TV scan speed: measure a ~50-channel PC probe after the TV scan ends, then decide.

## Blockers
- Game-room TV adb (192.168.68.150:5555) refused the connection at 10:45; 10:31 scan check not done, scan progress unknown (last seen 127 of 5,534, "Provider is busy, waiting"). TV may be asleep or adb off.
- Full suites, lint and `:app:assembleDebug` not yet run on any of these tips.

## Next priorities, in order
1. Step N on desktop (`:feature:live` `*HomeViewModelTest`, `*EpgViewModelTest`; `:feature:settings` `*SettingsBackupActionsTest`), `gradlew --stop` between runs.
2. Full suites + lint (baselines: settings 63 errors on master, core:ui 7, playback 5) + `:app:assembleDebug` on the integ tip, then merge `feat/channel-health-snapshot` into integ once green. Do not merge before.
3. Retry the TV scan screenshot (adb screencap only); after the scan ends, run the PC-vs-TV timing probe.
4. Ask Andrew (separately, multiple choice): security/auth/update/CI findings W19, W32, W34, W35, W37-W39, W42; two hard-coded English strings (`Media3PlayerEngine.kt:2537` "still buffering", `LiveTimeshiftManager.kt` "Local rewind stopped: low memory."); DASH byte cap; W5 (default leave); worktree cleanup (`.claude/worktrees/agent-*`); delete `secret.md` when scans end.
5. Run /codemap (CODEMAP built-at 82fa1b94 is 38 commits behind HEAD; new file `ChannelHealthSnapshot.kt`).
6. Update FINDINGS statuses; no CHANGELOG.md exists in this repo (none written).

## Risks
Stream ids may differ across resellers (name hash reduces this); label field always empty; removed-channel retention simplified; translations are Claude Code's own, not native-reviewed; no device test of the snapshot buttons yet.

## Open todos
#2 gate (A/B/C), #4 EPG #50, #5 re-probe (paused), #7 TV scan check, #8 agents on open findings, #13 verify (lint, assembleDebug, docs).
