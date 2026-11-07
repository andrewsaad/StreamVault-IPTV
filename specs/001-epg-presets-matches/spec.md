# Spec 001: EPG presets and matches linking

Status: clarified, ready for /architect
Date: 2026-11-06

## 1. Goal

A StreamVault user can switch on ready-made guide presets (Arabic beIN first, then Al Ahly and Egypt, then US sports), and the guide and the Sports tab then show current programs and matches on the right channels on every device, without hand-adding guide sources or channel ids. Today the provider supplies no guide data of its own, so most channels show an empty guide and matches cannot be linked to them.

## 2. User stories

### P1: Arabic beIN guide from a preset
A user turns on the "beIN" preset and the beIN channels (including the Vega, HD, H265, 4K, Low and SD variants) show their current and upcoming programs, and the Sports tab lists the matches on them.

Acceptance scenarios:
1. Given the beIN preset is off, when the user opens the preset list, then the beIN preset is shown as off and nothing has been downloaded.
2. Given the beIN preset is off, when the user turns it on, then within 2 minutes the beIN channels show a current program in the guide.
3. Given a channel named "beIN Sport 1 H265 SD" and a guide entry named "beIN SPORTS 1", when the preset is on, then the channel shows the guide entry's programs.
4. Given the preset is on and a match is scheduled on a beIN channel, when the user opens the Sports tab, then the match is listed with that channel offered to play it.

### P2: Al Ahly and Egypt guide from a preset
A user turns on the "Egypt" preset and the Al Ahly channels and the ON Sport channels show their programs, and Al Ahly matches appear in the Sports tab.

Acceptance scenarios:
1. Given the Egypt preset is on, when the user opens the guide, then the "Al Ahly" channels show a current program.
2. Given an Al Ahly match is scheduled on an ON Sport channel, when the user opens the Sports tab, then the match is listed with that channel offered.
3. Given a channel has no guide data in any enabled preset, when the user opens the guide, then the channel shows an empty row with no error.

### P3: US sports guide and matches
A user turns on the "US" preset and the national US networks (including NFL Network, NFL RedZone, ESPN, Fox, CBS and NBC) show their programs, and the Sports tab lists MLS (Inter Miami, San Diego FC), NFL and MLB (Padres) matches with channels the user can play them on.

Acceptance scenarios:
1. Given the US preset is on, when the user opens the guide, then "NFL Network HD [US]" shows a current program.
2. Given the schedule source offers MLS and an Inter Miami match is scheduled, when the user opens the Sports tab, then the match is listed and the generic MLS LIVE event channels are offered as candidates to play it.
3. Given the schedule source offers MLB and a Padres game is scheduled, when the user opens the Sports tab, then the game is listed with a regional sports channel offered if the provider carries one, or with no channel and a clear "no channel available" label if not.
4. Given a local or regional channel that the US preset has no entry for, when the user opens the guide, then that channel shows an empty row with no error.

### P2: Same presets available on every device
A user can turn each preset on separately on each device, and a device with the same preset on shows the same guide entries as any other device.

Acceptance scenarios:
1. Given the beIN preset is on for the Google TV, when the user opens the preset list on the Fire TV, then the beIN preset is shown as off there until the user turns it on.
2. Given the same app version on a Google TV and a Fire TV Stick, when the same preset is on for both, then both devices show the same guide entries for the same channel.
3. Given a preset is on for the Google TV, when the user turns it off on the Fire TV, then the Google TV's guide is unchanged.

## 3. Functional requirements

- FR-001: The app shall list the built-in guide presets with each preset's name, the regions or channels it covers, and an on or off state.
- FR-002: Every preset shall be off until the user turns it on.
- FR-003: The app shall download nothing for a preset while it is off.
- FR-004: Turning a preset on shall add its guide data for matching channels without the user entering a web address.
- FR-005: Turning a preset off shall remove its guide data from the guide and stop updating it.
- FR-006: The user shall be able to see and change the web address a preset uses.
- FR-007: The app shall refresh an enabled preset no more than twice in any 24 hours on a device.
- FR-008: The app shall show each preset's last successful update time and its last error, if any.
- FR-009: The app shall match a channel to a guide entry by exact name first.
- FR-010: When no exact name match exists, the app shall match by name after ignoring country or region tags, quality labels and separators.
- FR-010a: The app shall keep time-shift labels (such as "+1") when matching, so a time-shifted channel only matches a guide entry with the same time-shift label.
- FR-011: The app shall never replace a match that exact-name matching already made.
- FR-012: When several guide entries match a channel after ignoring those parts, the app shall pick the same entry every time for that channel.
- FR-013: A match the user set manually shall take priority over any automatic match.
- FR-014: The first-release presets shall be Arabic beIN, Al Ahly and Egypt, and US sports and national networks, in that priority order.
- FR-015: The Sports tab shall list matches for enabled leagues together with the channels that can play them.
- FR-016: When a match has no channel carrying its teams by name, the Sports tab shall offer the event-only channels (for example the MLS LIVE channels) as candidates.
- FR-017: When the Sports tab's existing schedule source offers the MLS, NFL or MLB leagues, the user shall be able to enable them in the Sports tab's league settings.
- FR-017a: When the existing schedule source does not offer one of those leagues, the app shall not list matches for it, and the feature's delivery report shall name the missing league.
- FR-018: A channel with no guide data shall show an empty guide row and no error.
- FR-019: When a preset's source cannot be reached, the app shall keep the last good guide data and show that the update failed.
- FR-020: The presets shall work the same on a Google TV and a Fire TV Stick with the same app version.
- FR-021: All new text the user sees shall be provided in every language listed in the app's supported-language list, with real translations and no English placeholders.
- FR-022: Existing hand-added guide sources shall keep working unchanged.
- FR-023: When a preset's source address stops working, a corrected address shall reach the user only through a new app build, and the user shall be able to enter a corrected address by hand on the affected device without waiting for a build.
- FR-023a: The app shall not read a preset list from any online location other than the preset's own guide file address.
- FR-024: Installing and using this feature shall not require removing the user's installed official StreamVault app or deleting its data.
- FR-025: The feature shall work in the separate side-by-side copy of the app on each device, which keeps its own data.
- FR-026: Each device shall keep its own on or off state for every preset; changing a preset on one device shall not change it on another.

## 4. Success criteria

- SC-001: With the beIN preset on, at least 80% of the provider's beIN sports channels (188 in the tested provider: beIN-named channels whose name contains "sport", "xtra" or "alkass") show a current program within 2 minutes of turning it on. Non-sport beIN channels (movies, series, kids, drama, 54 in the tested provider) are not counted because the guide files do not list them. This is a target; the match rate for beIN names was not measured before the spec.
- SC-002: With the US preset on, at least 50% of the channels in the provider's USA category (147 channels) show a current program. The estimate comes from a 12-channel sample and could be off by 15 to 20 points.
- SC-003: Every channel that is matched to a guide entry before the feature is switched on is matched to the same entry afterwards (zero changed matches).
- SC-004: Turning a preset on takes no more than 3 actions from the main screen.
- SC-005: An enabled preset refreshes at most 2 times in any 24 hours.
- SC-006: Turning on all first-release presets on a Fire TV Stick with about 1.5 GB of memory finishes without the app closing and without the guide becoming unusable.
- SC-007: Scrolling the guide with all first-release presets on is no worse than the pre-feature guide scrolling (no more than 6% janky frames over a 66-key scroll test on the Google TV debug build, baseline 4.2% to 5.9%).
- SC-008: For each of Inter Miami, San Diego FC, the NFL and the Padres, at least one upcoming match in the schedule data appears in the Sports tab with at least one candidate channel or a clear "no channel available" label.
- SC-009: 100% of new user-facing strings exist and are translated in all supported languages (38 listed today: English as the default plus 37 translations).
- SC-010: A source that cannot be reached leaves the previous guide data visible in 100% of tests.

## 5. Edge cases and failure handling

- The source file cannot be reached or returns an error: keep the last good data, show the failure and the time of the last success, retry only within the twice-a-day limit.
- The source file moves or is renamed: the update fails visibly; the user can edit the address (FR-006); see the open question on corrected addresses.
- The source file is very large for the device: the update must not make the app close; it fails visibly instead.
- A channel matches several entries (East, West and Pacific feeds, HD and SD, +1 time-shift): one entry is chosen consistently (FR-012).
- Two presets cover the same channel: the app uses one entry and does not show duplicate programs. [Assumed: the preset higher in the priority order wins.]
- The user turns a preset off while it is updating: the update stops and no guide data from it remains.
- A hand-added source and a preset cover the same channel: the existing exact matches stay (FR-011, FR-022).
- Channel names change when the provider re-syncs: matches are recomputed and the user's manual matches are kept.
- No preset covers a channel (Azerbaijan, Armenian, Iran, Kurdish and several others): the guide row is empty with no error (FR-018).
- Device storage is almost full: the update fails visibly and the app keeps working.
- Several devices update at the same time: each device's refresh limit applies on its own.
- Empty state: no preset on means the guide behaves exactly as it does today.

## 6. Out of scope

- Channels that have no public guide source (Azerbaijan, Armenian, Iran, Kurdish: 419 channels) and weak-coverage regions (Albania about 35%, Russia about 25%).
- Local and regional US channels that national guide files do not list.
- Getting guide data from the provider (the provider's own guide address returns "not found").
- Scraping websites for guide data.
- Fixing the Settings-to-Home issue on the Fire TV.
- Performance work on the guide grid.
- Recordings and downloads.
- Automatically turning presets on for the user.
- Telling which of several event-only channels is carrying a given match.
- Updating the user's installed official app in place; that needs the maintainer's signing key and is only possible through an upstream release.
- Sharing the official app's data (providers, favorites, history) with the side-by-side copy.
- Adding a new schedule data source for leagues the Sports tab's current source does not offer.

## 7. Assumptions

- Assumed the provider supplies no usable guide data (0 of 5537 channels have a guide id and its guide address returns "not found"), because that was measured on the user's provider.
- Assumed the public guide files (epgshare01) are acceptable as preset sources because Andrew chose opt-in presets; their terms are unclear and the host is a single volunteer site with no service guarantee.
- Assumed match-rate figures are estimates from hand samples of 8 to 12 channels, good to roughly plus or minus 15 to 20 points.
- Assumed one app build serves both Google TV and Fire TV, because the app has no per-device versions today.
- Assumed "Prades" in the request means the San Diego Padres, because it is the San Diego baseball team next to San Diego FC.
- Assumed the supported-language list has 38 entries (English plus 37 translations), because that is the count in the app's locale list; an earlier draft said 39 from a miscount.
- Assumed the preset higher in the priority order wins when two presets cover the same channel, because a rule is needed and the order matches the user's stated priority.
- Assumed the app cannot know which event-only channel carries a given match, so all event-only channels are offered as candidates.
- Assumed existing manual match overrides and the existing hand-added sources are kept as they are.
- Assumed the Sports tab's existing league settings screen is where the user enables leagues.

## 8. Clarifications

### Session 2026-11-06
- Q: The installed app on every device is the maintainer's signed build, so a build made here cannot update it without wiping its data. How should this feature reach Andrew's devices? → A: Side-by-side app now (separate app id, own data), propose the changes upstream later. Reflected in FR-024, FR-025 and Out of scope.
- Q: When Andrew turns a guide preset on for one device, should his other devices get it automatically? → A: No, each device opts in on its own. Reflected in the P2 story (now "Same presets available on every device"), its scenarios, and FR-026.
- Q: If MLS, NFL and MLB fixtures are not available to the Sports tab today, should adding them be in scope? → A: In scope only if the existing sports data source already offers them; otherwise report the gap and do not add a new source. Reflected in FR-017, FR-017a, the P3 scenarios and Out of scope.
- Q: If a preset's guide file moves or is renamed, how should the corrected address reach Andrew's devices? → A: Only through a new app build, plus the user can edit the address by hand on the affected device. Reflected in FR-023 and FR-023a.
- Q (2026-11-07, after the first device run): SC-001 measured 164 of 242 beIN channels (67.8%) because 54 non-sport beIN channels (Box Office, Series, Movies, Kids) are not in any guide file, and no alias can fix that. Should SC-001 count sports channels only? → A: Yes, Andrew chose sports channels only. SC-001 now counts beIN-named channels containing "sport", "xtra" or "alkass" (188). The criterion was changed after the first result, so the original 242-channel figure is kept in validation-us1.md.
- Q (design review): should a "+1" time-shift label be ignored or kept when matching names? → A: kept, because a "+1" channel matched to the base channel would show times an hour off. Reflected in FR-010 and FR-010a; plan.md section 5 already assumed this.
