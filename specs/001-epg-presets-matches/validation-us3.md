# US3 validation: US preset and Sports tab (T037)

Device: Google TV Streamer (game room), `com.streamvault.app.debug`, 2026-11-07. Evidence: read-only database copy (06:44 local time) and Sports tab screenshots.

## Live TV channels (database, programme covering now)

| Channel | Guide channel | Now programme |
|---|---|---|
| [US]ESPN_1HD | ESPN.HD.us2 | Live: College GameDay |
| [US]ESPN_2HD | ESPN2.(Alternate).us2 | SIGN OFF |
| ESPN U [US] | ESPNU.HD.us2 | Live: College GameDay |
| Espn News [US] | ESPNEWS.HD.us2 | United Grid League |
| FOX SPORT 1 [US], FOX Sport 1 [US] | FS1.Fox.Sports.1.HD.us2 | NASCAR Craftsman Truck Series |
| Fox Sports 2 [US], US: FOX Sport 2 | FS2.Fox.Sports.2.HD.us2 | UEFA Nations League Magazine |
| NBC Golf Channel[US] | Golf.Channel.HD.us2 | PGA Tour Golf |

All sampled ESPN, FS1, FS2 and Golf channels show a programme.

## SC-002 (at least 50% of the USA category shows a current programme)

The category with 147 channels (Category 24, matches the spec's 147): 80 mapped, 79 with a programme now = 53.7%. PASS (target 50%).

## Sports tab

"On now" view, league picker, exactly as shown: All leagues, EFL Championship, Formula 1, La Liga, Ligue 1, MotoGP, Premier League, UEFA Champions League. Sport filters: All, Football, Basketball, Motorsport, Other. No MLS, NFL, MLB or NBA entry.

"Upcoming" view: stayed on "Finding sports events..." with a spinner for more than 25 seconds and never loaded, so the Upcoming league picker could not be read.

## SC-008 (Inter Miami, San Diego FC, NFL, Padres each have an upcoming match in the Sports tab)

Not verified. None of the four appeared in the On now view or its league picker, and Upcoming did not load. FAIL/unverified: the leagues are not in the picker, and FR-017a (name any league the schedule data lacks) was not seen on screen either.

## Verdict

Live TV channels: PASS. SC-002: PASS (53.7%). SC-008: NOT MET.
