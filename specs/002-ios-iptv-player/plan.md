# Plan 002: iPhone IPTV player

Designs against `spec.md` (FR-001 to FR-043, SC-001 to SC-012). Date: 2026-11-06. Status: awaiting approval, no code written.

Clean-room note: no StreamVault source was opened to write this plan, but the AI that wrote it HAS read parts of StreamVault's source earlier in the same session (the EPG matching and favorites code). To keep the new project independent (FR-032, SC-009), the implementation must be done by fresh agents in a new repository that are never given access to the StreamVault checkout. They work only from this plan, the two specs, and a behavior document (`docs/BEHAVIOR.md`) written from observed app behavior and public provider conventions.

## 1. Goal

A new open-source SwiftUI app, built and signed entirely in the cloud, that Andrew installs on his iPhone over the air and uses to add several providers, browse and search one combined live channel list, keep reordered favorites, see now-and-next programs, and watch HLS and raw MPEG-TS channels.

## 2. Approach

**Native SwiftUI app, built from small Swift packages, with all logic that does not need iOS testable on a cheap Linux runner.**

- **Language and UI:** Swift 5.10+, SwiftUI, one app target. Minimum iOS 17 (assumed; Andrew gives the real version, FR-043).
- **Modules (Swift packages in one repo):**
  - `Core`: models, matching rules, name normalization, favorite ordering. Pure Swift, no Apple frameworks, so `swift test` runs on Linux.
  - `ProviderKit`: Xtream and playlist clients and parsers (URLSession, async/await).
  - `GuideKit`: guide file download, streaming XMLTV parser, preset catalog, 12-hour and 2-per-day refresh gate, loose-name matcher.
  - `Storage`: SQLite through GRDB (MIT license). One database file; Keychain for passwords.
  - `PlayerKit`: a `PlayerEngine` protocol with two engines: AVPlayer for HLS, a VLC-based engine for raw transport streams (LGPL, replaceable, FR-031a).
  - `App`: SwiftUI screens and view models.
- **Project generation:** the Xcode project is generated from a `project.yml` (XcodeGen) so everything is editable as text on Windows and reproducible in CI.
- **Cloud build and install:** a public GitHub repository gets free macOS runner minutes, so the iOS build costs nothing (to be confirmed against current GitHub terms). Pipeline: Linux job runs package tests; macOS job runs simulator tests, archives, signs with Andrew's paid account using secrets he adds himself, and uploads to TestFlight. Andrew installs over the air through TestFlight (FR-029, FR-030, SC-008).
- **Behavior from observation, not source:** `docs/BEHAVIOR.md` records how Xtream and playlist providers respond, how the guide matching rules work (exact name, then loose name keeping "+1", "sports" treated as "sport", trailing country codes dropped, existing exact matches never replaced) and the test fixtures, written as synthetic examples.

**Rejected alternative: Kotlin Multiplatform sharing StreamVault's domain code.** It would reuse logic but make the app a derivative under StreamVault's non-commercial license (conflicts with the permissive license chosen in clarification 1), and the cloud-only build loop with a Kotlin toolchain adds a second hard-to-debug stack.
**Rejected alternative: Apple's player only.** It cannot play raw transport streams, which is the second user story and SC-005.

## 3. Files touched

All new, in a new repository (suggested name `openiptv-ios`; Andrew chooses). Nothing in StreamVault changes except these spec files.

```
LICENSE                        MIT
THIRD_PARTY.md                 every dependency and its license (FR-031b)
README.md  docs/BEHAVIOR.md  docs/CLEAN_ROOM.md  docs/INSTALL_FROM_WINDOWS.md  docs/CI_SECRETS.md
project.yml                    XcodeGen project description
Package.swift                  Core, ProviderKit, GuideKit, Storage, PlayerKit targets + tests
Sources/Core/                  Provider.swift Channel.swift Favorite.swift Normalizer.swift GuideMatcher.swift
Sources/ProviderKit/           XtreamClient.swift PlaylistParser.swift ProviderVerifier.swift StreamURLBuilder.swift
Sources/GuideKit/              XMLTVParser.swift GuideSource.swift PresetCatalog.swift RefreshGate.swift GuideResolver.swift
Sources/Storage/               Database.swift Migrations.swift Repositories.swift KeychainStore.swift
Sources/PlayerKit/             PlayerEngine.swift AVPlayerEngine.swift VLCEngine.swift EngineSelector.swift
App/                           IPTVApp.swift Screens/ (AddProvider, ChannelList, Favorites, Player, Guide, Presets, Settings)
Tests/                         one test target per package, UITests/ for simulator smoke tests
.github/workflows/             linux-tests.yml  ios-build.yml  testflight.yml
fastlane/                      Fastfile  Appfile  (signing and TestFlight upload)
scripts/                       license-scan.sh (SC-009 check)  fixtures/ (synthetic only)
```

## 4. Interfaces and data flow

```swift
// Core
struct ProviderID: Hashable { let raw: UUID }
struct ChannelKey: Hashable { let provider: ProviderID; let streamID: String }   // favorites keyed by this (FR-038)
struct Channel { let key: ChannelKey; var name: String; var logoURL: URL?; var categoryID: String?
                 var epgID: String?; var container: Container }                   // .hls | .transportStream | .unknown
enum Normalizer { static func strict(_ s: String) -> String; static func loose(_ s: String) -> String }
struct MatchResult { let xmltvID: String; let kind: MatchKind /* exactID, exactName, looseName, manual */ }
enum GuideMatcher { static func match(channel: Channel, index: GuideIndex) -> MatchResult? }  // order fixed by FR-025, FR-026

// ProviderKit
protocol ProviderClient { func verify() async throws -> VerifiedProvider        // FR-003: wrongLogin | unreachable | unreadable
                          func liveCategories() async throws -> [Category]
                          func liveChannels() async throws -> [Channel]
                          func streamURL(for: Channel) -> URL }                  // credentials never logged

// Storage (GRDB tables)
// provider, category, channel(provider_id, stream_id, name, strict_key, loose_key, ...),
// favorite(provider_id, stream_id, position, added_at)  PK(provider_id, stream_id), positions in steps of 1024,
// guide_source(id, kind provider|preset, preset_id, url, enabled, last_attempt_at, last_success_at, last_error, etag),
// epg_channel(source_id, xmltv_id, display_name, strict_key, loose_key),
// epg_programme(source_id, xmltv_id, start, stop, title, description)  -- only a window of now-2h to now+36h kept,
// channel_guide(provider_id, stream_id, source_id, xmltv_id, match_kind, confidence)

// PlayerKit
protocol PlayerEngine { func play(_ url: URL); func stop(); var state: AsyncStream<PlaybackState> { get } }
enum EngineSelector { static func engine(for c: Channel, url: URL) -> EngineKind }  // m3u8 -> AVPlayer; ts or unknown -> VLC; fallback on failure
```

Flows:
1. **Add provider (FR-001 to FR-005):** form, `ProviderVerifier` classifies the failure, password goes to the Keychain, provider row to SQLite, then categories and channels load in one transaction.
2. **Combined list (FR-035 to FR-041):** the channel list is one database query over all providers, ordered by provider and category; each row carries a provider label. A refresh runs one task per provider in a task group, so one unreachable provider only produces a warning.
3. **Search (FR-014):** the strict and loose keys are stored per channel; a search normalizes the query once and filters an in-memory array of (key, id) pairs, which is fast enough for 11,000 rows (SC-003, SC-011).
4. **Favorites (FR-015 to FR-017, FR-038):** the favorite row is keyed by (provider, stream id), so a provider refresh never touches it; removed channels stay as "unavailable" rows.
5. **Play (FR-009 to FR-013, FR-042):** `EngineSelector` picks an engine, a failure on the first engine tries the other once, then shows a message with retry; the scene phase observer stops playback when the app leaves the screen.
6. **Guide (FR-020 to FR-027, FR-041):** the provider's own guide first, then enabled presets; the 12-hour and 2-per-24-hour gate sits in `RefreshGate` using stored attempt times (failures count); the XMLTV file is parsed as a stream and only the time window is stored; matching runs per provider after each import.

## 5. Edge cases and failure modes

| Case (spec) | Behavior |
|---|---|
| Wrong login, unreachable, unreadable (FR-003) | Three typed errors, three messages, nothing saved. |
| 11,000 channels (SC-011) | Database import in one transaction on a background task; the list opens from the stored copy first (spec edge case). |
| Provider refuses a second connection (FR-028) | Detected from the provider's refusal response and shown as its own message; the playing channel is kept. |
| Network changes during playback | Engine reports a stall; the UI shows reconnecting and retries; after 30 s a failure message with retry. |
| Duplicate names across providers (FR-037) | Two rows, each with its own provider label and favorite state. |
| Raw transport stream that the VLC engine cannot open | Message names the failure; SC-005 allows 1 of 5 sampled channels to fail. |
| Huge guide file | Streamed, not loaded whole; only the time window is stored; failure keeps the last good guide. |
| TestFlight build expires after 90 days | A scheduled workflow re-uploads a fresh build; documented in `docs/INSTALL_FROM_WINDOWS.md`. |
| Signing secrets missing | The pipeline fails with a clear message before building; no credential ever appears in the repo or logs. |
| VLC package unavailable or too large | Spike first (see section 8); the `PlayerEngine` protocol keeps the choice replaceable. |

## 6. Test plan

Linux (`swift test`, cheap and fast, no Mac needed): `NormalizerTests` (tags, prefixes, quality words, accents, "+1" kept, "sports" to "sport", trailing country code), `GuideMatcherTests` (exact beats loose, deterministic tie-break, existing match never replaced, empty guide row), `XtreamParsingTests` (JSON fixtures: valid, empty, HTML error page, wrong login), `PlaylistParserTests`, `XMLTVParserTests` (streamed, window filter, bad time zone), `RefreshGateTests` (2 per 24 h including failures), `FavoriteOrderTests` (move, persist, gaps), `StreamURLBuilderTests` (no credential in log output).

macOS simulator (CI): `DatabaseMigrationTests`, `FavoritesStoreTests` (survive refresh, SC-004), `CombinedListTests` (two providers, one down, SC-012), `SearchPerformanceTests` (11,000 rows under 500 ms, SC-011), `EngineSelectorTests`, `ProviderVerifierTests` against a local mock server, and XCUITest smoke tests: `AddProviderUITests`, `FavoritesReorderUITests`.

Device (Andrew, manual, recorded in `docs/VALIDATION.md`): `HLS zap median under 4 s` (SC-002), `raw transport stream 4 of 5 channels for 2 minutes` (SC-005), `beIN and US preset match rates` (SC-006), memory under 300 MB and 450 MB (SC-010, SC-011), `install to update in 30 minutes` (SC-008).

Pipeline gates: `scripts/license-scan.sh` fails the build if any file shares long identical passages with a stored fingerprint list of the StreamVault checkout (SC-009), and `THIRD_PARTY.md` must list every dependency (FR-031b).

## 7. Agent recommendation

This needs ~4 agents (complexity: high), each in a fresh worktree of the NEW repository, none with access to the StreamVault checkout:
1. **Core, ProviderKit, behavior doc:** models, normalizer, matcher, Xtream and playlist parsing, fixtures, `docs/BEHAVIOR.md` (written from the specs and observed behavior only).
2. **GuideKit and Storage:** XMLTV streaming parser, presets, refresh gate, GRDB schema and repositories.
3. **App and PlayerKit:** SwiftUI screens, view models, engine selection, scene-phase handling.
4. **Pipeline and docs:** Package and project generation, Linux and macOS workflows, signing and TestFlight lane, license scan, install guide.
A VLC feasibility spike (agent 3, first task) gates the raw transport stream story. Device validation stays with Andrew because the iPhone is his and Claude Code has no way to reach it from this Windows PC.

## 8. Points that need Andrew's decision

1. **Repository and app name**, and public from the start (needed for free macOS minutes).
2. **Install route:** DECIDED 2026-11-06 by Andrew: TestFlight over the air (uses the paid account, 90-day build expiry, scheduled re-upload workflow).
3. **Minimum iOS:** 17 assumed; Andrew gives his iPhone model and iOS version.
4. **VLC spike first:** the video library's current Swift package support must be checked before the raw transport story is promised.
5. **Secrets:** Andrew adds the Apple team id, signing certificate and App Store Connect API key as repository secrets himself; Claude Code supplies the steps in `docs/CI_SECRETS.md` and never receives the values.
