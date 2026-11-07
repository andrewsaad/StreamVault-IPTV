# Alias retest on Google TV (game room, debug app), 2026-11-06

Build: feat/epg-presets at f5e09b7a (includes epg/us-aliases), installed over the previous debug build, app launched once, DB read after about 90 s.
Measured from channel_epg_mappings joined to channels by name pattern (external source count / channels with that name).

| Name pattern | External matches | Channels |
|---|---|---|
| beIN% | 150 | 229 (65%, was 50% in SC-001 run) |
| %Alkass% | 22 | 23 |
| %ESPN% | 8 | 13 |
| %Fox Sport% | 4 | 4 |
| ON Sport% | 3 | 23 (still low) |

Whole library: 829 loose-name, 52 exact-name, 27 manual, 4629 unmatched.
Not measured: SC-002 on its own definition, Sports tab picker (MLS/NFL/MLB), screenshots of guide rows.
Sources all synced with no error: beIN Arabic, beIN Qatar, AE1, US2, US_SPORTS1.

## Retest 2: beIN word-order fix (75de2be7), installed 20:11 on both TVs

Game room (presets on): beIN 175 of 229 external matches (was 150); Xtra 15/15, EN 6/6, FR 4/4; ESPN 8/13; Alkass 22/23.
Whole library: 854 loose-name, 52 exact, 27 manual, 4604 unmatched.
Living room (192.168.68.168): presets not enabled on this device (per-device by design); only 27 manual mappings, beIN 27/229.
Remaining beIN gaps are non-sports channels (Movies, Series, Star and similar) that the guide files do not list.
