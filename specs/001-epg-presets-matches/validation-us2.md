# US2 validation: Egypt, Alkass and Al Ahly (T030)

Device: Google TV Streamer (game room), `com.streamvault.app.debug`, 2026-11-07. Evidence: read-only database copy (06:44 local time, programme covering now) and the TV Guide screenshots. These channels were not individually opened on screen (the app was left on Live TV Favorites and Guide); results are from the database.

| Channel | Matched guide channel | Match type | Now programme |
|---|---|---|---|
| ON Sport HD 1080, ON Sport LOW, ON Sport 4K | On.Time.Sports.HD.ae | NORMALIZED_NAME_LOOSE | Football Match |
| ON Sport Max, Plus, Event 1 to 4 variants | none | none | none |
| Al Ahly [HD], Al Ahly [SD] | Al.Ahly.TV.HD.ae | NORMALIZED_NAME_LOOSE | Football Match |
| Alkass three | Alkass.Three.ae | NORMALIZED_NAME | a non-sport programme (Gold Fever) |
| Alkass One, Two, BeIN Alkass 1 to 4 | Alkass_1..4_EN.bein | NORMALIZED_NAME_LOOSE | none covering now (18 to 20 programmes loaded) |
| Alkass 5, BeIN Alkass 5 to 8 | Alkass_5..8_EN.bein | NORMALIZED_NAME_LOOSE | none (0 programmes in the feed) |
| Alkass Fuor | none | none | none |

Counts: plain ON Sport (3 of 3 variants) and Al Ahly (2 of 2) show a programme. Alkass: 1 of the Alkass-named channels shows one now; the matched BeIN-style Alkass feeds have no programme covering this hour or no data.

Verdict: plain ON Sport PASS, Al Ahly PASS, Alkass PARTIAL (matches exist, guide data gaps). No SC number applies to Egypt in spec.md; not marking the task complete.
