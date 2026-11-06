# Tasks 001: EPG presets and matches linking

Source: `spec.md` (4 stories, FR-001 to FR-026, SC-001 to SC-010) and `plan.md`. Date: 2026-11-06.
Base branch for all work: `perf/guide-and-data` (includes the perf commits and the launch-intent guard). Every Gradle run uses `--no-build-cache`.
`[P]` means the task touches different files from every incomplete task before it and depends on none of them.

## Phase 1: Setup

- [ ] T001 Add loose-match fixtures (tagged provider channel names and sample guide channel ids for beIN, Egypt and US) in data/src/test/resources/epg_presets/provider_channel_names.txt and data/src/test/resources/epg_presets/guide_channel_ids.txt

## Phase 2: Foundational (blocks every story)

- [ ] T002 [P] Add EpgPreset, EpgPresetSource and EpgPresetCatalog (bein, egypt, us; epgshare01 addresses) in domain/src/main/java/com/streamvault/domain/model/EpgPreset.kt
- [ ] T003 [P] Add EpgPresetStatus and the EpgPresetManager interface in domain/src/main/java/com/streamvault/domain/repository/EpgPresetManager.kt
- [ ] T004 [P] Add NORMALIZED_NAME_LOOSE to EpgMatchType in domain/src/main/java/com/streamvault/domain/model/EpgModels.kt
- [ ] T005 [P] Add the device-local EpgPresetStateStore (enabled presets, owned source ids, last attempt time), mirroring data/src/main/java/com/streamvault/data/preferences/EmptyGuideKeyCache.kt, in data/src/main/java/com/streamvault/data/epg/EpgPresetStateStore.kt
- [ ] T006 Write EpgPresetCatalogTest (unique ids and addresses, every address passes UrlSecurityPolicy, no online preset list) in data/src/test/java/com/streamvault/data/epg/EpgPresetCatalogTest.kt

## Phase 3: User story 1 (P1) beIN guide from a preset

Tests first.
- [ ] T007 [P] [US1] Write EpgNameNormalizerLooseTest (tags, prefixes, quality words, accents, "+1" kept, ids like Freeform.HD.us2) in data/src/test/java/com/streamvault/data/epg/EpgNameNormalizerLooseTest.kt
- [ ] T008 [US1] Write EpgResolutionEngineLooseMatchTest (exact beats loose, loose fills only unresolved, deterministic tie-break, manual override wins, before/after regression fixture) in data/src/test/java/com/streamvault/data/epg/EpgResolutionEngineLooseMatchTest.kt
- [ ] T009 [US1] Write EpgPresetManagerImplTest (enable, disable, shared address reference count, reconcile, refresh failure keeps data, off by default downloads nothing) in data/src/test/java/com/streamvault/data/epg/EpgPresetManagerImplTest.kt
- [ ] T010 [US1] Write EpgSourceRepositoryPresetRefreshCapTest (at most 2 attempts in 24 hours including failures; hand-added source keeps the 5-minute rule) in data/src/test/java/com/streamvault/data/repository/EpgSourceRepositoryPresetRefreshCapTest.kt
- [ ] T016 [US1] Write SettingsEpgPresetViewModelTest (state mapping, off by default, status and error text, refreshing) in feature/settings/src/test/java/com/streamvault/feature/settings/presentation/SettingsEpgPresetViewModelTest.kt
- [ ] T021 [P] [US1] Write SettingsStringsLocaleParityTest (every new key present with matching placeholders in the default file and all 38 locale folders) in feature/settings/src/test/java/com/streamvault/feature/settings/SettingsStringsLocaleParityTest.kt

Implementation.
- [ ] T011 [US1] Implement normalizeLoose and looseKeys in data/src/main/java/com/streamvault/data/epg/EpgNameNormalizer.kt
- [ ] T012 [US1] Add the loose-name index, step 3b and the deterministic tie-break in data/src/main/java/com/streamvault/data/epg/EpgResolutionEngine.kt
- [ ] T013 [US1] Add the 12-hour minimum gap between attempts for preset-owned sources in data/src/main/java/com/streamvault/data/repository/EpgSourceRepositoryImpl.kt
- [ ] T014 [US1] Implement EpgPresetManagerImpl (setEnabled, refreshNow, reconcile, observe) in data/src/main/java/com/streamvault/data/epg/EpgPresetManagerImpl.kt
- [ ] T015 [US1] Bind EpgPresetManager and EpgPresetStateStore in data/src/main/java/com/streamvault/data/di/EpgPresetModule.kt
- [ ] T017 [US1] Add the presets list state to feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsUiStateModel.kt
- [ ] T018 [US1] Add preset actions (toggle, refresh now, edit address) in feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsEpgActions.kt
- [ ] T019 [US1] Wire the presets flow and actions into feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsViewModel.kt
- [ ] T020 [US1] Add the "Guide presets" section (on/off row, status line, address edit) in feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsEpgSection.kt
- [ ] T022 [US1] Add the default English strings (about 14 keys) in feature/settings/src/main/res/values/strings.xml
- [ ] T023 [US1] Add real translations of the new keys in the 38 locale files feature/settings/src/main/res/values-*/strings.xml
- [ ] T024 [US1] Validate beIN on the Google TV (SC-001, SC-004) and record the result in specs/001-epg-presets-matches/validation-us1.md

## Phase 4: User story 2 (P2) Al Ahly and Egypt

- [ ] T025 [P] [US2] Add Egypt fixtures (Al Ahly [HD] and [SD], ON Sport variants) in data/src/test/resources/epg_presets/egypt_names.txt
- [ ] T026 [US2] Write EpgPresetEgyptMatchTest in data/src/test/java/com/streamvault/data/epg/EpgPresetEgyptMatchTest.kt
- [ ] T027 [US2] Check the AE1 channel list for Al Ahly and ON Sport entries and adjust the egypt preset sources in domain/src/main/java/com/streamvault/domain/model/EpgPreset.kt
- [ ] T028 [US2] Write SportsChannelFallbackMatcherAlAhlyTest (an Al Ahly match offers the Al Ahly channels) in feature/live/src/test/java/com/streamvault/feature/live/sports/SportsChannelFallbackMatcherAlAhlyTest.kt
- [ ] T029 [US2] Add the Al Ahly team alias if T028 shows it is missing in feature/live/src/main/java/com/streamvault/feature/live/sports/SportsMetadataRegistry.kt
- [ ] T030 [US2] Validate Egypt on the Google TV and record the result in specs/001-epg-presets-matches/validation-us2.md

## Phase 5: User story 3 (P3) US sports guide and matches

- [ ] T031 [P] [US3] Add US fixtures (sample of the 147 USA channel names, sample US2 guide ids) in data/src/test/resources/epg_presets/us_names.txt and data/src/test/resources/epg_presets/us2_ids.txt
- [ ] T032 [US3] Write EpgPresetUsMatchTest (at least 50% of the sampled USA names match, NFL Network HD [US] matches) in data/src/test/java/com/streamvault/data/epg/EpgPresetUsMatchTest.kt
- [ ] T033 [P] [US3] Write SportsChannelFallbackMatcherEventOnlyTest (MLS LIVE n | Event Only offered for an MLS event, not for an NBA event, limit unchanged) in feature/live/src/test/java/com/streamvault/feature/live/sports/SportsChannelFallbackMatcherEventOnlyTest.kt
- [ ] T034 [US3] Add the EVENT_ONLY_LEAGUE match reason in feature/live/src/main/java/com/streamvault/feature/live/sports/SportsChannelFallbackMatcher.kt
- [ ] T035 [US3] Write SportsMetadataRegistryUsLeaguesTest (MLS, NFL, MLB, Inter Miami, San Diego FC, Padres) in feature/live/src/test/java/com/streamvault/feature/live/sports/SportsMetadataRegistryUsLeaguesTest.kt
- [ ] T036 [US3] Add missing league and team aliases found by T035 in feature/live/src/main/java/com/streamvault/feature/live/sports/SportsMetadataRegistry.kt
- [ ] T037 [US3] Validate the US preset and the Sports tab (Inter Miami, San Diego FC, NFL, Padres; SC-002, SC-008, name any league the schedule data lacks per FR-017a) on the Google TV in specs/001-epg-presets-matches/validation-us3.md

## Phase 6: User story 4 (P2) Same presets available on every device

- [ ] T038 [P] [US4] Write EpgPresetStateStorePerDeviceTest (state is per device, changing one does not change another) in data/src/test/java/com/streamvault/data/epg/EpgPresetStateStorePerDeviceTest.kt
- [ ] T039 [US4] Install the side-by-side build on the Fire TV, enable all three presets, and check SC-006 and same-entry parity with the Google TV in specs/001-epg-presets-matches/validation-us4.md
- [ ] T040 [US4] Confirm the official app and its data are untouched on both devices (FR-024, FR-025) in specs/001-epg-presets-matches/validation-us4.md

## Phase 7: Polish

- [ ] T041 [P] Add docs/EPG_PRESETS.md (preset list, sources, terms note, how to edit an address)
- [ ] T042 [P] Add a changelog entry in docs/CHANGELOG.md
- [ ] T043 Run the full unit suites for domain, data, feature:settings and feature:live, lint, and an uncached debug build, and record output in specs/001-epg-presets-matches/validation-gate.md
- [ ] T044 Measure guide scrolling with all three presets on (SC-007) in specs/001-epg-presets-matches/validation-sc007.md
- [ ] T045 Commit specs/001-epg-presets-matches/spec.md, plan.md and tasks.md as a docs commit
- [ ] T046 Refresh CODEMAP.md and CODEMAP_FULL.jsonl for the new files

## Dependencies

- Phase 1 (T001) before the tests that read fixtures (T008, T026, T032).
- Phase 2 blocks all stories: T002, T003, T004, T005 can run together; T006 needs T002.
- Story 1 internal order: tests T007 to T010, T016, T021 first. T011 follows T007; T012 needs T004, T008 and T011; T013 needs T005 and T010; T014 needs T002, T003, T005, T009, T012 and T013; T015 needs T014; T017 to T020 are sequential (same view-model files) and need T003 and T016; T022 before T023 (same key names); T023 needs T021 and T022; T024 needs everything above in this phase.
- Story 2 needs Story 1 complete: T025, then T026 and T027 (T027 edits the file T002 created, so it waits for T002 only), T028 then T029, T030 last. T029 and T036 share SportsMetadataRegistry.kt, so T036 waits for T029.
- Story 3 needs Story 1 for the matching machinery: T031, T032; T033 then T034; T035 then T036; T037 last.
- Story 4: T038 after T005; T039 needs the Fire TV reachable over adb, T040 after T039.
- Polish: T041 and T042 any time after the stories; T043 after all code; T044 after T043 and after the guide perf decision; T045 and T046 last.

Parallel groups (after their prerequisites):
- Group A (foundation): T002, T003, T004, T005.
- Group B (Story 1 tests): T007, T008, T010, T016, T021 touch different files and can run together once Group A is done. T009 can join once T002, T003 and T005 exist.
- Group C (independent tracks): the data and domain track (T011 to T015), the settings UI track (T017 to T020), the Sports matcher track (T028, T029, T033 to T036) and the translations track (T022, T023) touch disjoint files and can run together, with the settings UI track starting once T003 exists and the translations track once the key names are fixed in T022.
- Group D (polish): T041 and T042 together.

## Coverage

| Requirement | Tasks |
|---|---|
| US1 (beIN preset) | T007 to T024 |
| US2 (Al Ahly and Egypt) | T025 to T030 |
| US3 (US sports) | T031 to T037 |
| US4 (same presets on every device) | T038 to T040 |
| FR-001 | T014, T017, T020 |
| FR-002, FR-003 | T009, T014 |
| FR-004, FR-005 | T009, T014 |
| FR-006 | T018, T020 |
| FR-007 | T010, T013 |
| FR-008 | T016, T017, T020 |
| FR-009 | T007, T011, T012 |
| FR-010, FR-010a | T007, T011 |
| FR-011, FR-012, FR-013 | T008, T012 |
| FR-014 | T002, T006 |
| FR-015 | T033, T037 |
| FR-016 | T033, T034 |
| FR-017, FR-017a | T035, T036, T037 |
| FR-018 | T008, T024 |
| FR-019 | T009, T014 |
| FR-020 | T039 |
| FR-021 | T021, T022, T023 |
| FR-022 | T009, T010, T013 |
| FR-023, FR-023a | T006, T018 |
| FR-024, FR-025 | T040 |
| FR-026 | T005, T038 |
| SC-001 | T024 |
| SC-002 | T032, T037 |
| SC-003 | T008 |
| SC-004 | T024 |
| SC-005 | T010 |
| SC-006 | T039 |
| SC-007 | T044 |
| SC-008 | T037 |
| SC-009 | T021, T023 |
| SC-010 | T009 |

Requirements with zero tasks: none.
Tasks that serve no requirement: T001 (shared fixtures for T008, T026, T032), T041, T042 (documentation), T043 (the release gate), T045 and T046 (repository rules in CLAUDE.md: specs and code map kept current). These are support or process tasks; none is a feature task.

Design and spec contradictions to flag:
1. Spec User story 2 expects the Al Ahly channels to show a current program, but only AE1 is bundled for Egypt (the Egypt-specific file was last updated in 2024) and AE1 was not checked for Al Ahly. T027 decides; if AE1 has no entry, the acceptance scenario cannot be met by the guide and is met only through the Sports tab, so the spec needs a wording fix.
2. FR-010 was changed after the design review (time-shift labels kept, FR-010a); plan.md already matches.
3. SC-007 depends on the guide scroll comparison, which is still being repeated with a like-for-like cold-start procedure (the earlier after-runs were colder than the baseline).

## Agent recommendation

This needs ~4 agents (complexity: high). The largest parallel group is four disjoint tracks (data and domain; settings UI; Sports matcher; translations), each in its own worktree from `perf/guide-and-data`, Gradle with `--no-build-cache`. Device validation tasks (T024, T030, T037, T039, T040, T044) stay serial with Claude Code because there is one Google TV and the Fire TV is offline.
