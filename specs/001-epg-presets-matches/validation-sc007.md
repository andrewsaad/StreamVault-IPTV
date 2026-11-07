# SC-007 validation: guide scroll jank (T044)

Device: Google TV Streamer (game room), `com.streamvault.app.debug`, 2026-11-07. All first-release presets on (about 284,000 programmes loaded). Method: `dumpsys gfxinfo` reset before each pass, then 25 DPAD_DOWN, 8 DPAD_RIGHT, 25 DPAD_UP, 8 DPAD_LEFT, 0.15 s apart (66 keys), in the TV Guide showing the Favorites list (13 channels), no warm-up before pass 1.

| Pass | Frames | Janky | p90 | p99 |
|---|---|---|---|---|
| 1 | 655 | 78 (11.91%) | 53 ms | 200 ms |
| 2 | 592 | 57 (9.63%) | 48 ms | 150 ms |
| 3 | 602 | 56 (9.30%) | 42 ms | 150 ms |

Target: no more than 6% janky frames (baseline 4.2% to 5.9%). Result: 9.3% to 11.9%. FAIL.

Caveats: the guide showed only 13 favorite channels, so vertical movement stopped at the list end. No pre-feature baseline file exists on this branch, so the comparison is against the spec's quoted baseline only.
