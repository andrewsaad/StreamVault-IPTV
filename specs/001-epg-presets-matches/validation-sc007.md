# SC-007 validation: guide scroll jank (T044)

Device: Google TV Streamer (game room), `com.streamvault.app.debug`, 2026-11-07. All first-release presets on (about 284,000 programmes loaded). Method: `dumpsys gfxinfo` reset before each pass, then 25 DPAD_DOWN, 8 DPAD_RIGHT, 25 DPAD_UP, 8 DPAD_LEFT, 0.15 s apart (66 keys), in the TV Guide showing the Favorites list (13 channels), no warm-up before pass 1.

| Pass | Frames | Janky | p90 | p99 |
|---|---|---|---|---|
| 1 | 655 | 78 (11.91%) | 53 ms | 200 ms |
| 2 | 592 | 57 (9.63%) | 48 ms | 150 ms |
| 3 | 602 | 56 (9.30%) | 42 ms | 150 ms |

Target: no more than 6% janky frames (baseline 4.2% to 5.9%). Result: 9.3% to 11.9%. FAIL.

Caveats: the guide showed only 13 favorite channels, so vertical movement stopped at the list end. No pre-feature baseline file exists on this branch, so the comparison is against the spec's quoted baseline only.

## Full-guide re-measurement (2026-11-07)

Device: Google TV Streamer (game room), `com.streamvault.app.debug`. Guide Filter category: All Channels (5536 channels listed in the selector). Method: `dumpsys gfxinfo` reset before each pass, then 160 DPAD_DOWN presses (0.15 s sleep, in 8 device-side batches of 20, wake-up key before each batch because the TV sleeps mid-run), starting at channel row 1, no warm-up before pass 1. First attempt was discarded because the TV went to sleep mid-pass.

| Pass | Frames | Janky | p50 | p90 | p95 | p99 |
|---|---|---|---|---|---|---|
| 1 | 2116 | 267 (12.62%) | 23 ms | 53 ms | 73 ms | 129 ms |
| 2 | 1874 | 310 (16.54%) | 25 ms | 65 ms | 97 ms | 200 ms |
| 3 | 2087 | 219 (10.49%) | 23 ms | 48 ms | 69 ms | 113 ms |

Extra pass 4 (same method, not counted): 1707 frames, 302 janky (17.69%), p50 25 ms.

Target: no more than 6% janky frames. Result: 10.49% to 16.54%. FAIL.

Programme coverage (DB pulled read-only after the run, 5537 channels, 284,251 EPG programmes): 933 channels have an EPG mapping and 890 of 5537 channels have a programme covering the current time (about 16%).

Caveats:
- No valid baseline (presets off or no EPG data in the same All Channels list) could be run without changing app settings, so there is no same-list comparison. The spec's quoted 4.2% to 5.9% baseline is the only reference.
- Focus position after a 160-press pass was back near row 1 (rows 1 to 5 visible after pass 4; row 20 after pass 3), so the list appears to wrap or reset rather than travel 160 rows. How far each pass actually scrolled was not verified, only that rows moved 1:1 with keys in a separate 20-press check (row 20 to 40).
- The Guide category was left on All Channels (a Favorites re-select did not register); the TV was returned to Home with no playback (0 sessions in state 3).
