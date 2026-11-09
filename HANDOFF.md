# HANDOFF (2026-11-09, written mid-session)

Branch of record: `feat/channel-health` (tip 0825e8e8 when written). It contains `feat/epg-presets` plus everything below.
Nothing from this session was pushed except `feat/epg-presets` (pushed to the fork at 2163a91a on 2026-11-08) and
`fix/refresh-rate-zap` (logging commit). GitHub Issues are disabled on the fork; findings live in FINDINGS.md.

## What is merged on feat/channel-health, and its test status

| Area | State |
|---|---|
| EPG presets fixes F1-F22 (F18 reverted as won't-fix: it left the guide empty for up to 12 h after off/on) | tests passed in earlier runs |
| F13 one guide match pass per provider; nit 7 import only the edited address | passed |
| Refresh-rate matching kept across a zap (stop() no longer clears the vote, grace 15 s) | passed, verified on the game room TV by log |
| Rebroadcast rule in the Sports tab (drop a later airing of the same match 4 h to 15 h after the first ended) | passed (feature:live 296); not seen on a screen |
| In-app Channel clean-up (scan, review, hide): domain contract, data/health, Settings screen, give-up message, search entry, cleanup on provider delete, retry of provider-error channels, repeated-503 rule | data 2217 and settings tests passed at the last full run; the last three fixes below have NOT compiled |
| W2, W3, W33, W4 follow-up (internal provider URLs only) | W2, W3 tested; W33, W4 follow-up not compiled |
| Settings strings rounds 1-2, mojibake repair in 7 locales + SettingsStringsEncodingTest, SettingsLogSanitizer (30 Log.w sites), required text lambdas, restore messages, generic EPG error | settings 1198 passed before the sanitizer/message commit; the last commit (447cd81a, 722328b2) has NOT compiled |

NOT VERIFIED: the full gate on tip 0825e8e8. Three attempts (gate7 pre-merge: 5253 tests, 1 failure fixed; gate8, run A) were
stopped by the harness for low memory. Run in three steps with Gradle stopped between them:
A `:data:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin`, B `:feature:settings:testDebugUnitTest :domain:test`,
C the other modules, `lintDebug`, `:app:assembleDebug`. Lint always reports the same 63 pre-existing errors.

## PC memory problem (why builds keep being stopped)

Windows shows about 5.7 GB of paged pool and 1.4 GB of nonpaged pool in use (normal is well under 1 GB), process working
sets about 6.6 GB, so a 3 GB Gradle run leaves about 2 GB free and the harness stops it. A reboot is the likely fix. Stale
Chrome processes are stopped before builds (Andrew's standing OK).

## Devices

- Game room TV (192.168.68.150:38189, debug app only, muted with `appops PLAY_AUDIO deny`): runs the in-app channel scan
  (started 2026-11-08 22:27, resumed 08:41 on 2026-11-09). Keep-awake loop script: scratchpad keepalive_gameroom.ps1 (8 h).
  Installed build is OLDER than the branch tip; rebuild and reinstall after the gate.
- Living room TV (192.168.68.168): read-only log pulls only. The official app `com.streamvault.app` must never be touched.
- The provider allows 1 connection: any scan or playback blocks the others.

## Scans

- PC scan (probe_full.py in the session scratchpad) is OFF at 1,072 of 5,537 channels (917 OK, 133 provider errors, 21 empty, 1 dead).
- In-app scan reached channel id 736 on 2026-11-09 morning, 5 stored failures. Two scans together cover about 20%.
- Findings: 25 channels failing, 12 are Bally Sports (retired brands); all 6 beIN Sport EN channels return HTTP 503 every time;
  FR channels mixed. A channel is suggested for hiding only after failing on two separate days.
- `secret.md` (gitignored) holds the provider login for the PC scan: delete it when the scans are done.

## Open items and decisions (Andrew decided on 2026-11-09)

1. Guide check #50: two-hour offset between the Qatar guide rows and the other beIN guide file (114 of 247 pairs); matches seen
   on screen are often in no guide; French beIN channels (Sport 3, Max 4-10) have no guide on the game room TV. Next: preview ~30
   sports channels at kickoff, use kickoff time to decide which file is right, fix matching/time shift with tests.
2. Scroll jank SC-007: 7.2-7.4% against 6%, no presets-off baseline (it would delete 27 manual beIN mappings). Andrew chose to fix:
   task #54, start with W1 (`LiveGuideGrid.kt:73-78`), re-measure with /c/tmp/sv_jank.sh.
3. FINDINGS.md backlog: W1, W5-W42; next fixes W1 (with #54). W33, W4, W2, W3 done.
4. Fire TV items (#30, #33) wait for the Fire TV; #38 needs a game live on an Arabic feed; #36 iPhone app deferred as its own project;
   #31/#32 blocked by the manual-mappings risk and an unmapped Settings press route.
5. Translations written by agents are unreviewed by a native speaker. One older Norwegian line (`values-nb`, settings_auto_sync_explanation)
   contains a replacement character and needs a real fix.
6. Publishing: push both branches to the fork, no pull request (Andrew's choice), after the gate passes.
