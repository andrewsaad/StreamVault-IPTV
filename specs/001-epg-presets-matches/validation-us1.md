# US1 validation: beIN preset (T024)

Device: Google TV Streamer (game room), `com.streamvault.app.debug`, 2026-11-07. Evidence: screenshots of Live TV (Favorites) and the TV Guide, plus a read-only copy of the app database pulled at 06:44 local time (epg_programmes rows with start <= now < end).

## Named channels (database, programme covering now)

| Channel | Matched guide channel | Match type | Now programme |
|---|---|---|---|
| beIN Sport 1 FHD Q | beIN SPORTS 1.qa | MANUAL | Leeds United vs Tottenham Hotspur |
| beIN Sport 2 FHD Q | beIN SPORTS 2.qa | MANUAL | Wrexham vs Wolverhampton Wanderers |
| beIN_SPORTS_1Xtra_1080p, HD720, Low, VEGA | beIN SPORTS XTRA 1.qa | NORMALIZED_NAME_LOOSE | beIN SPORTS XTRA live coverage block |
| beIN Sport 1 EN HD | beIN SPORTS EN 1.qa | NORMALIZED_NAME_LOOSE | Arsenal vs Hull City |

All four show a programme in the database. On screen (Live TV Favorites and the TV Guide) beIN Sport 1 to 9 FHD Q and beIN_SPORTS_1Xtra to 4Xtra all showed programme titles, none showed "No schedule information" (13 of 13 favorites). beIN Sport 1 EN HD is not a favorite and was not visible on screen; it is confirmed from the database only.

## 8 sampled beIN channels

beIN Sport 1 FHD Q, beIN Sport 2 FHD Q, beIN Sport 4 FHD, beIN Sport 2 FR FHD, beIN_SPORTS_1Xtra_1080p, beIN Sport 1 EN HD, beIN_SPORTS_2_HD [TR] and beIN Sport 9 FHD Q: 8 of 8 show a programme now.

## SC-001 (at least 80% of beIN channels show a current programme)

Channels whose name contains "bein": 242 (matches the spec's 242).
- Mapped to a guide channel: 183 (75.6%).
- Mapped and with a programme covering now: 164 (67.8%).
- Not mapped: 59, mostly non-sport beIN names (Box Office, Series, Movies Premiere [TR]) with no sport guide equivalent.
- Mapped but nothing covering now: 19 (BeIN Alkass 1 to 8 variants; the Alkass guide feeds hold 0 to 20 programmes with gaps at this hour).

Result: 67.8% against the 80% target. FAIL on the raw count. The shortfall is the non-sport beIN names and the Alkass feeds, not the sport channels named above.

## SC-004 (3 actions or fewer to turn a preset on)

Not measured in this session (presets were already on). NOT RUN.

## Verdict

Named channels and sample: PASS. SC-001 as written: FAIL (67.8%). SC-004: NOT RUN.
