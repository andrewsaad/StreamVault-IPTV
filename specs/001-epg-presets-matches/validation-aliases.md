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
