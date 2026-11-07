# Spec 002: iPhone IPTV player

Status: clarified, ready for /architect
Date: 2026-11-06
Note: this spec is kept in the StreamVault repository for now; it moves to the new app's own repository when that exists.

## 1. Goal

Andrew can add his IPTV provider on an iPhone, browse and search its live channels, keep a reordered favorites list, see what is on now and next, and watch live channels, all from an open-source app he builds without owning a Mac. Today StreamVault only runs on Android TV, Google TV and Fire TV, so there is no way to watch his provider on an iPhone.

## 2. User stories

### P1: Add a provider and watch a live channel
Andrew enters his provider details, sees the provider's live categories and channels, picks a channel, and it plays.

Acceptance scenarios:
1. Given the app has no provider, when Andrew enters an Xtream server address, username and password that are correct, then the live categories and channels appear without further steps.
2. Given the app has no provider, when Andrew enters a playlist address that is valid, then the playlist's channels appear grouped by their group names.
3. Given a provider is loaded, when Andrew selects an HLS channel, then video starts playing and a loading indicator shows until the first frame.
4. Given Andrew enters a wrong password, when he saves the provider, then the app reports a wrong-login message and does not save the provider.

### P2: Find channels and keep favorites
Andrew searches the channel list by name, marks channels as favorites, and puts the favorites in his own order.

Acceptance scenarios:
1. Given 5,500 channels are loaded, when Andrew types part of a channel name, then matching channels are listed.
2. Given a channel is listed, when Andrew marks it as a favorite, then it appears in the favorites list.
3. Given three favorites, when Andrew moves the third to first place, then the order is the new order after closing and reopening the app.
4. Given a favorite exists, when the provider's channel list is refreshed and the channel is still offered, then it is still a favorite in the same position.

### P2: Watch channels that use raw transport streams
Channels that the provider serves as raw transport streams (not HLS) play the same way as HLS channels.

Acceptance scenarios:
1. Given a channel served as a raw transport stream, when Andrew selects it, then video and sound start.
2. Given playback of a raw transport stream, when Andrew selects the next channel, then the new channel replaces it.
3. Given a stream that cannot be played, when playback fails, then a message names the failure and offers a retry.

### P2: Build and install without a Mac
Andrew can produce an iPhone build from a code change and install it on his own iPhone from a Windows PC.

Acceptance scenarios:
1. Given a change is pushed to the repository, when the cloud build runs, then it produces an installable build or reports what failed.
2. Given an installable build, when Andrew installs it from his Windows PC onto his iPhone, then the app opens.
3. Given the install is signed with Andrew's paid Apple developer account, when a year passes, then Andrew can reinstall a newly signed build without losing his stored provider and favorites.
4. Given a build signed in the cloud pipeline, when Andrew opens his iPhone's install channel (over the air), then he can install or update the app without connecting the iPhone to a computer.

### P3: Use several providers as one channel list
Andrew adds more than one provider and sees all their live channels together, each labelled with its provider.

Acceptance scenarios:
1. Given one provider is saved, when Andrew adds a second provider with correct details, then the channel list shows both providers' channels with a provider label on each.
2. Given two providers offer a channel with the same name, when Andrew searches for it, then both appear, labelled, and each can be a favorite on its own.
3. Given one provider is unreachable at refresh, when the list loads, then the other provider's channels are shown with a warning naming the unreachable provider.
4. Given two providers, when Andrew removes one after the confirmation, then its channels, favorites and guide data are gone and the other provider is unchanged.

### P3: See what is on now and next
Andrew sees the current and next program for channels, from the provider's own guide if it has one or from opt-in guide presets.

Acceptance scenarios:
1. Given a provider that serves a guide, when Andrew opens a channel, then the current and next programs are shown.
2. Given a guide preset is off, when Andrew looks at the preset list, then the preset is shown as off and nothing has been downloaded.
3. Given the beIN preset is turned on, when the guide is imported, then beIN channels with tags such as "FHD Q" show their programs.
4. Given a channel with no guide data, when Andrew opens it, then the guide row is empty and no error is shown.

## 3. Functional requirements

- FR-001: The app shall let the user add a provider by Xtream server address, username and password.
- FR-002: The app shall let the user add a provider by playlist address.
- FR-003: The app shall verify a new provider before saving it and report wrong login, unreachable server and unreadable response as three different messages.
- FR-004: The app shall keep provider details and favorites on the device only.
- FR-005: The app shall keep provider passwords unreadable by other apps.
- FR-006: The app shall load a provider's live categories and channels.
- FR-007: The app shall let the user browse channels by category.
- FR-008: The app shall show each channel's name and logo, and a placeholder when a logo is missing.
- FR-009: The app shall play the channel the user selects.
- FR-010: The app shall show a loading indicator until the first frame and a failure message with a retry action when playback fails.
- FR-011: The app shall play channels served as HLS.
- FR-012: The app shall play channels served as raw transport streams.
- FR-013: The app shall let the user switch to the next or previous channel while playing.
- FR-014: The app shall let the user search channels by name, ignoring case and accents.
- FR-015: The app shall let the user add and remove a favorite from the channel list and from the player.
- FR-016: The app shall let the user reorder favorites and keep the order after the app restarts.
- FR-017: The app shall keep a favorite attached to the same channel when the provider's channel list is refreshed.
- FR-018: The app shall refresh the channel list when the user asks and no more than once every 12 hours on its own.
- FR-019: The app shall show the last loaded channels when the device is offline.
- FR-020: The app shall show the current and next program for a channel when guide data exists.
- FR-021: The app shall use the provider's own guide when the provider offers one.
- FR-022: The app shall offer opt-in guide presets that are off by default and download nothing until turned on.
- FR-023: The app shall refresh an enabled guide preset no more than twice in any 24 hours.
- FR-024: The user shall be able to see and change the address of a guide preset.
- FR-025: The app shall match channels to guide entries by exact name first, then by name after ignoring region tags, quality words and separators, and shall keep time-shift labels such as "+1".
- FR-026: The app shall never replace a channel's exact-name guide match with a looser match.
- FR-027: The app shall show an empty guide row, not an error, for a channel without guide data.
- FR-028: The app shall show the provider's "connection limit reached" or "account in use" refusal as a distinct message when the provider sends it.
- FR-029: The project shall build an installable app on a cloud macOS machine without the user owning a Mac.
- FR-030: The project shall document how to install a build on an iPhone from a Windows PC.
- FR-031: The project shall be published as open source under a permissive license (MIT or Apache 2.0).
- FR-031a: The app may rely on a third-party video player component under a weak-copyleft license (LGPL), and that component shall be kept replaceable and documented as such.
- FR-031b: The project shall list every third-party component with its license.
- FR-032: The project shall contain no StreamVault source code and shall state that it is an independent, unofficial project.
- FR-033: The project shall contain no provider credentials, channel lists or guide data from any provider.
- FR-034: The app shall collect no analytics and show no advertising.
- FR-035: The app shall let the user add several providers and present their live channels as one combined channel list.
- FR-036: The app shall label every channel in the combined list with the provider it comes from.
- FR-037: The app shall list channels with the same name from different providers separately, each with its own favorite state.
- FR-038: The app shall keep each favorite tied to its own provider's channel, so a favorite is never moved to another provider's channel of the same name.
- FR-039: The app shall keep working with the other providers' channels and show a warning when one provider cannot be reached.
- FR-040: The app shall let the user remove a provider after confirmation, which removes that provider's channels, favorites and guide data.
- FR-041: The app shall refresh and match guide data for each provider on its own.
- FR-042: The app shall stop playback when it leaves the screen or the phone is locked.
- FR-043: The app shall run on Andrew's own iPhone on its current iOS version, and shall state which iPhone models and iOS versions it was tested on.

## 4. Success criteria

- SC-001: A new user with correct provider details reaches playing video in no more than 6 taps from first launch.
- SC-002: Playback of an HLS channel starts within a median of 4 seconds of selecting it on the same network where the Android app measured a median of 2.5 seconds, and never exceeds 10 seconds in a 30-zap test.
- SC-003: With 5,500 channels loaded, a name search returns results in under 300 ms and scrolling the channel list drops fewer than 5% of frames.
- SC-004: Favorites order and membership survive 100% of 20 repeated app restarts and 10 channel list refreshes in testing.
- SC-005: At least 4 of 5 sampled raw transport-stream channels play for 2 minutes without a stall longer than 3 seconds.
- SC-006: With the beIN preset on, at least 80% of the provider's beIN channels (242 in the tested provider) show a current program, and with the US preset on at least 50% of the USA category (147 channels), matching the targets of spec 001.
- SC-007: Every enabled guide preset refreshes at most 2 times in any 24 hours.
- SC-008: From a pushed code change to the new build running on Andrew's iPhone takes no more than 30 minutes of elapsed time, with no Mac owned by Andrew.
- SC-009: A scripted review of the repository finds 0 files copied from StreamVault source.
- SC-010: With 5,500 channels and a guide loaded, the app uses no more than 300 MB of memory.
- SC-011: With two providers of 5,500 channels each (11,000 channels), a name search returns results in under 500 ms and the app uses no more than 450 MB of memory.
- SC-012: With one of two providers unreachable, the other provider's channels are visible within 3 seconds of opening the app and the warning names the unreachable provider.

## 5. Edge cases and failure handling

- Wrong login, unreachable server, or a page that is not a channel list: three distinct messages (FR-003); nothing is saved.
- A provider with thousands of channels: the list opens within 2 seconds on the second launch using the saved copy while a refresh runs.
- The provider refuses a second connection (account in use): a distinct message (FR-028); the previous channel keeps playing if it was playing.
- The network drops or switches between Wi-Fi and cellular during playback: playback shows a reconnecting state and retries; after 30 seconds it shows a failure message with a retry action.
- The app is sent to the background or the phone is locked while playing: playback stops, and coming back shows the channel list with the last channel selected, not a frozen frame (FR-042).
- A channel is renamed or removed at the next refresh: favorites for channels still offered stay; favorites for removed channels are shown as unavailable, not silently deleted.
- Two channels share a name: both are listed; each keeps its own favorite state.
- The guide file is huge or cannot be reached: keep the last good guide, show the failure and the time of the last success.
- A guide matches several entries for one channel (east and west feeds, HD and SD): one entry is chosen consistently.
- The installed build stops opening after the signing period ends: the user reinstalls without losing stored data; this is documented.
- Empty state, no provider: the app shows only the add-provider screen.
- Provider credentials are typed with trailing spaces: they are trimmed before use.

## 6. Out of scope

- Recordings and downloads.
- Movies, series and any video on demand.
- Trakt, torrent playback, casting and any Android TV, Google TV or Fire TV feature.
- Distribution through the App Store.
- iPad-specific layouts, Apple TV and Mac versions.
- Sharing favorites or settings between devices.
- Parental controls, subtitles and multi-view.
- Reusing any StreamVault source code.
- Background audio, lock-screen playback controls and picture-in-picture.

## 7. Assumptions

- Assumed the app is for iPhone only, because the request is for an iPhone app.
- Assumed Andrew has a paid Apple developer account, because he said he has one; its details (signing keys) are added by Andrew himself as private pipeline secrets and are never written into the repository or this specification.
- Assumed a sideloading tool that runs on Windows exists for personal use, because such tools are widely used; its limits (signing period, device limit) are Apple's and the tool's, not this project's.
- Assumed the cloud macOS build is paid or limited by the cloud provider, and that Andrew accepts that cost.
- Assumed the first provider is the Xtream provider already in use, whose live channels are HLS with some raw transport streams, because that is the provider tested on the Android app.
- Assumed "reuses behavior, not code" means the author may run the Android app and read its test fixtures and public protocol behavior, but may not copy or translate its source files, because that keeps the new project independent.
- Assumed guide presets use the same epgshare01 files and terms as spec 001, with the same unclear terms, because the matching design was done there.
- Assumed Andrew will tell the model and iOS version of his iPhone before design starts, because the supported device is only his own iPhone on its current iOS version; other iPhones and versions are not promised.
- Assumed the 12-hour automatic refresh and 2-per-day preset limit are acceptable, because they match the Android feature's limits.
- Assumed a message-only experience for failures is enough in the first release, because there is no support channel beyond the open-source repository.

## 8. Clarifications

### Session 2026-11-06
- Q: Which open-source license, and may the video player depend on a third-party library whose license limits sharing? → A: A permissive license (MIT or Apache 2.0), and an LGPL player library is allowed. Reflected in FR-031, FR-031a and FR-031b.
- Q: Plan around a free Apple ID (7-day installs) or a paid developer account? → A: Andrew has a paid developer account and will provide its details later. Reflected in the install story's scenarios 3 and 4 and in the Assumptions; signing keys stay as private pipeline secrets.
- Q: One provider enough for the first release, or several? → A: Several providers combined into one channel list. Reflected in a new P3 story, FR-035 to FR-041, SC-011, SC-012. Placed at P3 so the P1 slice (one provider, watch a channel) stays small; the data design must still allow several providers from the start.
- Q: Should video keep playing in the background or in picture-in-picture? → A: No for now; playback stops when the app is not on screen. Reflected in FR-042, the background edge case and Out of scope.
- Q: Which iPhone models and iOS versions must be supported? → A: Andrew's own iPhone only, on its current iOS version. Reflected in FR-043 and the Assumptions.
