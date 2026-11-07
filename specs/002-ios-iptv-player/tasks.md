# Tasks 002: iPhone IPTV player

Source: `spec.md` (6 stories, FR-001 to FR-043 including FR-031a and FR-031b, SC-001 to SC-012) and `plan.md`. Date: 2026-11-06.
All paths are in the NEW repository (suggested name `openiptv-ios`), not in StreamVault. CODEMAP.md describes StreamVault and does not apply; no StreamVault file is touched.
Implementation agents work in fresh worktrees of the new repository and are NOT given the StreamVault checkout (clean-room rule, plan.md).
Stories: US1 add a provider and watch a channel (P1), US2 search and favorites (P2), US3 raw transport streams (P2), US4 build and install without a Mac (P2), US5 several providers as one list (P3), US6 now and next guide (P3).
`[P]` means the task touches different files from every incomplete task before it and depends on none of them.

## Phase 1: Setup

- [ ] T001 Create LICENSE (MIT) and README.md in the repository root
- [ ] T002 [P] Add THIRD_PARTY.md and docs/CLEAN_ROOM.md
- [ ] T003 [P] Add Package.swift with the Core, ProviderKit, GuideKit, Storage and PlayerKit targets and their test targets
- [ ] T004 Add project.yml (XcodeGen description of the app target and UI test target) in project.yml
- [ ] T005 Add .github/workflows/linux-tests.yml (runs swift test for the pure packages)
- [ ] T006 Add .github/workflows/ios-build.yml (generates the project, builds and runs simulator tests on a macOS runner)
- [ ] T007 Run the VLC feasibility spike (Swift package support, size, LGPL handling) and record the result in docs/SPIKE_VLC.md

## Phase 2: Foundational (blocks every story)

- [ ] T008 Write docs/BEHAVIOR.md (provider responses, matching rules, favorites rules) from the specs and observed behavior only
- [ ] T009 [P] Add synthetic fixtures (Xtream JSON, playlist, guide file; no real provider data) in scripts/fixtures/
- [ ] T010 Add the Core models in Sources/Core/Provider.swift, Sources/Core/Channel.swift and Sources/Core/Favorite.swift
- [ ] T011 Write NormalizerTests in Tests/CoreTests/NormalizerTests.swift
- [ ] T012 Implement Normalizer (strict and loose keys) in Sources/Core/Normalizer.swift
- [ ] T013 Write DatabaseMigrationTests in Tests/StorageTests/DatabaseMigrationTests.swift
- [ ] T014 Implement the database and its migrations in Sources/Storage/Database.swift and Sources/Storage/Migrations.swift
- [ ] T015 Write KeychainStoreTests in Tests/StorageTests/KeychainStoreTests.swift
- [ ] T016 Implement KeychainStore in Sources/Storage/KeychainStore.swift
- [ ] T017 Add the app shell and dependency container in App/IPTVApp.swift and App/AppEnvironment.swift

## Phase 3: User story 1 (P1) add a provider and watch a live channel

Tests first.
- [ ] T018 [P] [US1] Write XtreamParsingTests (valid, empty, error page, wrong login) in Tests/ProviderKitTests/XtreamParsingTests.swift
- [ ] T019 [P] [US1] Write PlaylistParserTests in Tests/ProviderKitTests/PlaylistParserTests.swift
- [ ] T020 [P] [US1] Write ProviderVerifierTests (wrong login, unreachable, unreadable are three results) in Tests/ProviderKitTests/ProviderVerifierTests.swift
- [ ] T021 [P] [US1] Write StreamURLBuilderTests (no credential appears in log output) in Tests/ProviderKitTests/StreamURLBuilderTests.swift
- [ ] T022 [US1] Write ProviderRepositoryTests (import of categories and channels in one transaction, offline reload) in Tests/StorageTests/ProviderRepositoryTests.swift
- [ ] T023 [US1] Write EngineSelectorTests in Tests/PlayerKitTests/EngineSelectorTests.swift
- [ ] T031 [US1] Write AddProviderViewModelTests and PlayerLifecycleTests (playback stops when the app leaves the screen) in Tests/AppTests/AddProviderViewModelTests.swift and Tests/AppTests/PlayerLifecycleTests.swift

Implementation.
- [ ] T024 [US1] Implement XtreamClient in Sources/ProviderKit/XtreamClient.swift
- [ ] T025 [US1] Implement PlaylistParser in Sources/ProviderKit/PlaylistParser.swift
- [ ] T026 [US1] Implement ProviderVerifier in Sources/ProviderKit/ProviderVerifier.swift
- [ ] T027 [US1] Implement StreamURLBuilder in Sources/ProviderKit/StreamURLBuilder.swift
- [ ] T028 [US1] Implement provider, category and channel import and reads in Sources/Storage/Repositories.swift
- [ ] T029 [US1] Add the PlayerEngine protocol and EngineSelector in Sources/PlayerKit/PlayerEngine.swift and Sources/PlayerKit/EngineSelector.swift
- [ ] T030 [US1] Implement AVPlayerEngine for HLS in Sources/PlayerKit/AVPlayerEngine.swift
- [ ] T032 [US1] Add the add-provider screen and its view model in App/Screens/AddProviderView.swift and App/Screens/AddProviderViewModel.swift
- [ ] T033 [US1] Add the channel list screen (categories, names, logos with placeholder, stored copy first) in App/Screens/ChannelListView.swift
- [ ] T034 [US1] Add the player screen (loading, failure with retry, next and previous channel, stop on leaving the screen) in App/Screens/PlayerView.swift
- [ ] T035 [US1] Write AddProviderUITests (add a provider, reach playing video in no more than 6 taps) in UITests/AddProviderUITests.swift

## Phase 4: User story 2 (P2) search and favorites

- [ ] T036 [P] [US2] Write FavoriteOrderTests in Tests/CoreTests/FavoriteOrderTests.swift
- [ ] T037 [P] [US2] Write SearchTests (case and accent insensitive) in Tests/CoreTests/SearchTests.swift
- [ ] T038 [US2] Write FavoritesStoreTests (survive 10 refreshes and 20 restarts, removed channels shown as unavailable) in Tests/StorageTests/FavoritesStoreTests.swift
- [ ] T039 [US2] Implement favorite ordering (gapped positions, move) in Sources/Core/Favorite.swift
- [ ] T040 [US2] Implement channel search in Sources/Core/Search.swift
- [ ] T041 [US2] Implement the favorites store in Sources/Storage/Repositories.swift
- [ ] T042 [US2] Add the favorites screen with reorder in App/Screens/FavoritesView.swift
- [ ] T043 [US2] Add the search field to App/Screens/ChannelListView.swift
- [ ] T044 [US2] Write FavoritesReorderUITests in UITests/FavoritesReorderUITests.swift

## Phase 5: User story 3 (P2) raw transport streams

- [ ] T045 [US3] Write PlaybackCoordinatorTests (engine fallback, retry message, connection-refused message) in Tests/PlayerKitTests/PlaybackCoordinatorTests.swift
- [ ] T046 [US3] Implement VLCEngine in Sources/PlayerKit/VLCEngine.swift
- [ ] T047 [US3] Implement PlaybackCoordinator (engine choice, fallback once, reconnect after 30 seconds, provider refusal detection) in Sources/PlayerKit/PlaybackCoordinator.swift

## Phase 6: User story 4 (P2) build and install without a Mac

- [ ] T048 [US4] Add the signing and TestFlight upload lanes in fastlane/Fastfile and fastlane/Appfile
- [ ] T049 [US4] Add the build, sign and upload workflow in .github/workflows/testflight.yml
- [ ] T050 [US4] Add the scheduled re-upload workflow (builds expire after 90 days) in .github/workflows/testflight-refresh.yml
- [ ] T051 [US4] Write docs/CI_SECRETS.md (Andrew adds the secrets himself) and docs/INSTALL_FROM_WINDOWS.md

## Phase 7: User story 5 (P3) several providers as one list

- [ ] T052 [P] [US5] Write CombinedListTests (labels, same-name channels, one provider down) in Tests/StorageTests/CombinedListTests.swift
- [ ] T053 [P] [US5] Write SearchPerformanceTests (11,000 channels under 500 ms) in Tests/StorageTests/SearchPerformanceTests.swift
- [ ] T054 [P] [US5] Write RefreshCoordinatorTests (independent refresh, partial failure, 12-hour limit) in Tests/ProviderKitTests/RefreshCoordinatorTests.swift
- [ ] T055 [US5] Write ProviderRemovalTests in Tests/StorageTests/ProviderRemovalTests.swift
- [ ] T056 [US5] Implement the combined query and provider labels in Sources/Storage/Repositories.swift
- [ ] T057 [US5] Implement RefreshCoordinator in Sources/ProviderKit/RefreshCoordinator.swift
- [ ] T058 [US5] Add the providers screen (add, remove with confirmation, warnings) in App/Screens/ProvidersView.swift

## Phase 8: User story 6 (P3) now and next guide

- [ ] T059 [P] [US6] Write XMLTVParserTests (streamed, time window, bad time zone) in Tests/GuideKitTests/XMLTVParserTests.swift
- [ ] T060 [P] [US6] Write GuideMatcherTests (exact first, loose after, existing match never replaced, empty row) in Tests/CoreTests/GuideMatcherTests.swift
- [ ] T061 [P] [US6] Write RefreshGateTests (2 per 24 hours including failures) in Tests/GuideKitTests/RefreshGateTests.swift
- [ ] T062 [P] [US6] Write PresetCatalogTests (off by default, addresses editable) in Tests/GuideKitTests/PresetCatalogTests.swift
- [ ] T063 [US6] Implement XMLTVParser in Sources/GuideKit/XMLTVParser.swift
- [ ] T064 [US6] Implement GuideMatcher in Sources/Core/GuideMatcher.swift
- [ ] T065 [US6] Implement RefreshGate in Sources/GuideKit/RefreshGate.swift
- [ ] T066 [US6] Implement PresetCatalog and GuideSource in Sources/GuideKit/PresetCatalog.swift and Sources/GuideKit/GuideSource.swift
- [ ] T067 [US6] Implement GuideResolver (provider guide first, presets next, per provider) in Sources/GuideKit/GuideResolver.swift
- [ ] T068 [US6] Add the guide and presets screens in App/Screens/GuideView.swift and App/Screens/PresetsView.swift

## Phase 9: Polish

- [ ] T069 Add the license scan and the fingerprint list (hashes only, produced once by Claude Code from the StreamVault checkout) in scripts/license-scan.sh and scripts/streamvault_fingerprints.txt
- [ ] T070 [P] Update THIRD_PARTY.md with the final dependency list and confirm there is no analytics or ad component
- [ ] T071 Run the device checks (zap time, raw stream playback, match rates, memory, tested models) and record them in docs/VALIDATION.md
- [ ] T072 Install the first TestFlight build on Andrew's iPhone and time the update path (SC-008), recorded in docs/VALIDATION.md
- [ ] T073 [P] Update README.md with build, install and contribution instructions

## Dependencies

- Phase 1: T001 first; T002 and T003 can run together; T004 needs T003; T005 needs T003; T006 needs T004; T007 can start as soon as T003 exists and gates T046.
- Phase 2 blocks all stories: T008 and T009 can run together; T010 first among the code tasks; T011 then T012; T013 then T014; T015 then T016; T017 needs T004.
- Story 1: tests T018 to T021 can run together; T022 needs T014; T023 needs T029's types (write both in order T029 then T023 compile); T031 needs T017; implementation T024 to T027 each follow their test; T028 needs T022 and T014; T029 then T030; T032 needs T031, T024 to T028; T033 needs T028; T034 needs T029, T030, T031; T035 needs T032 to T034.
- Story 2 needs Story 1's channel list: T036 and T037 can run together; T038 needs T028; T039 after T036; T040 after T037; T041 after T038 (same file as T028, so it waits for T028); T042 needs T041; T043 edits the file from T033 so it waits for T033; T044 last.
- Story 3 needs T029, T030 and T007: T045 first; T046 needs T007; T047 needs T045 and T046.
- Story 4 is independent of the code stories (only needs T004 and T006): T048, then T049, then T050; T051 can run with T049.
- Story 5 needs Story 2: T052, T053, T054 can run together; T055 after T052; T056 after T052 and T041 (same file); T057 after T054; T058 after T056 and T057.
- Story 6 needs Story 1: T059, T060, T061, T062 can run together; T063 to T066 each follow their test and use different files (can run together after their tests); T067 needs T063 to T066; T068 needs T067.
- Polish: T070 and T073 any time after the stories; T069 after T001; T071 needs Stories 1 to 3 and 6 and T072; T072 needs T049 and Andrew's secrets from T051.

Parallel groups:
- Group A (setup docs and build files): T002, T003.
- Group B (Story 1 tests): T018, T019, T020, T021.
- Group C (Story 2 tests): T036, T037.
- Group D (Stories 5 and 6 tests): T052, T053, T054, T059, T060, T061, T062 touch different files and can run together.
- Four independent tracks once the foundation exists, owning disjoint files: provider and core logic (T018 to T027, T036 to T040, T059 to T066 matching parts), storage (T013 to T016, T022, T028, T038, T041, T055, T056), app and player (T017, T029 to T035, T042 to T047, T058, T068), and pipeline and docs (T001 to T007, T048 to T051, T069 to T073). The three Repositories.swift tasks (T028, T041, T056) stay with one agent in that order; the two ChannelListView.swift tasks (T033, T043) stay with one agent in that order.

## Coverage

| Requirement | Tasks |
|---|---|
| US1 add a provider and watch | T018 to T035 |
| US2 search and favorites | T036 to T044 |
| US3 raw transport streams | T045 to T047 |
| US4 build and install | T048 to T051, T072 |
| US5 several providers | T052 to T058 |
| US6 now and next guide | T059 to T068 |
| FR-001 | T018, T024, T032 |
| FR-002 | T019, T025 |
| FR-003 | T020, T026 |
| FR-004 | T014, T022, T028 |
| FR-005 | T015, T016 |
| FR-006 | T024, T028 |
| FR-007, FR-008 | T033 |
| FR-009 | T029, T030, T034 |
| FR-010 | T034, T047 |
| FR-011 | T030 |
| FR-012 | T045, T046 |
| FR-013 | T034 |
| FR-014 | T037, T040, T043 |
| FR-015 | T038, T041, T042 |
| FR-016 | T036, T039, T042, T044 |
| FR-017 | T038, T041 |
| FR-018 | T054, T057 |
| FR-019 | T022, T033 |
| FR-020, FR-021 | T067, T068 |
| FR-022 | T062, T066, T068 |
| FR-023 | T061, T065 |
| FR-024 | T066, T068 |
| FR-025, FR-026 | T060, T064 |
| FR-027 | T060, T068 |
| FR-028 | T045, T047 |
| FR-029 | T006, T049 |
| FR-030 | T051 |
| FR-031 | T001 |
| FR-031a | T007, T046 |
| FR-031b | T002, T070 |
| FR-032 | T002, T008, T069 |
| FR-033 | T009, T069 |
| FR-034 | T070 |
| FR-035, FR-036 | T056, T058 |
| FR-037 | T052 |
| FR-038 | T038, T041, T052 |
| FR-039 | T054, T057 |
| FR-040 | T055, T058 |
| FR-041 | T067 |
| FR-042 | T031, T034 |
| FR-043 | T071, T072 |
| SC-001 | T035, T071 |
| SC-002 | T071 |
| SC-003 | T053, T071 |
| SC-004 | T038, T044 |
| SC-005 | T071 |
| SC-006 | T071 |
| SC-007 | T061 |
| SC-008 | T072 |
| SC-009 | T069 |
| SC-010 | T071 |
| SC-011 | T053, T071 |
| SC-012 | T052, T054 |

Requirements with zero tasks: none.
Tasks that serve no requirement: T003, T004, T005 (build setup), T017 (app shell), T073 (README). These are support tasks, not feature tasks.

Design and spec contradictions to flag:
1. Plan assumes a public repository gets free macOS runner minutes; that was not verified against current GitHub terms. T006 and T049 depend on it.
2. Plan assumes TestFlight internal testing needs no beta review for Andrew's own build. Not verified; T072 will show it.
3. T069 needs StreamVault fingerprints, which only Claude Code (not the implementation agents) may produce from the StreamVault checkout, as one-way hashes.
4. FR-005 and the plan keep passwords in the Keychain, which needs a device or simulator to test; T015 therefore runs only on the macOS runner, not on the Linux job.
5. The spec leaves the minimum iOS version to the iPhone's current version (FR-043); the plan assumes iOS 17. T017 and T004 use 17 until Andrew gives his iPhone model and iOS version.

## Agent recommendation

This needs ~4 agents (complexity: high). The largest parallel group is four disjoint tracks (provider and core logic, storage, app and player, pipeline and docs), each in a fresh worktree of the new repository with no access to the StreamVault checkout. T007 (VLC spike) runs first on the app and player track and gates T046. Device tasks (T071, T072) and the secrets for T049 stay with Andrew because Claude Code has no path to his iPhone or Apple account.
