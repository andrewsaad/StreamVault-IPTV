# Plan 001: EPG presets and matches linking

Designs against `spec.md` (FR-001 to FR-026, SC-001 to SC-010). Date: 2026-11-06. Status: awaiting approval, no code written.

## 1. Goal

A user switches on a built-in guide preset (beIN, Egypt, US) on a device and, after one import, the guide and the Sports tab show current programs and matches on the right channels, with no hand-typed source address, no channel ids, and no change to matches that already work.

## 2. Approach

Four small additions on top of machinery that already exists (external guide sources, source-to-provider assignments, a resolution engine, a Sports tab with a channel matcher):

1. **Preset catalog.** A fixed list in the domain module: id, display name key, coverage text key, one or more guide-file addresses. First release: `bein` (BEIN1 + AE1), `egypt` (AE1), `us` (US2 + US_SPORTS1), all from epgshare01. A preset is off until switched on (FR-001 to FR-003).
2. **Preset manager.** Turning a preset on creates (or reuses, when two presets share an address) an ordinary guide source, assigns it to every provider with a lower priority than any existing source (FR-022), imports it once, then re-resolves matches. Turning it off unassigns and deletes the source only when no other enabled preset still uses it. Per-device state (which presets are on, which source rows they own, last attempt time) lives in a small DataStore-backed store, so nothing is shared between devices (FR-026).
3. **Loose-name fallback.** The resolution engine gets one extra step after exact id and exact normalized name: a second key that ignores country or region tags, quality words and separators. The key is computed in memory at resolve time from the guide channel's display name and id, so no database column changes (FR-009 to FR-012).
4. **Event-only channel rule.** The Sports tab's channel matcher gets one scoring reason so channels named "... Event Only" with a league name (for example the 7 "MLS LIVE n" channels) are offered as candidates for matches in that league (FR-016).

Refresh limiting (FR-007): preset-owned sources get a 12-hour minimum gap between attempts, enforced inside the existing source refresh using the preset store's last-attempt time. Sources the user added by hand keep today's 5-minute rule (FR-022).

**Rejected alternative A: a `preset_id` column on the guide sources table.** Cleaner single source of truth, but it needs a Room migration (database is at v92), a committed schema export, multi-hop migration tests per `docs/DATABASE_MIGRATION_INVARIANTS.md`, and a backup format change, all for state that is device-local anyway (FR-026).
**Rejected alternative B: an online preset list.** Already ruled out by clarification Q4 (FR-023a).

## 3. Files touched

New:
- `domain/.../model/EpgPreset.kt`: `EpgPreset`, `EpgPresetSource`, `EpgPresetCatalog` (the static list).
- `domain/.../repository/EpgPresetManager.kt`: interface.
- `data/.../epg/EpgPresetManagerImpl.kt`: implementation.
- `data/.../epg/EpgPresetStateStore.kt`: DataStore-backed per-device state (mirror the existing store pattern in `data/.../preferences/`, to be confirmed when implementing).
- Tests (section 6).

Modified:
- `data/.../epg/EpgNameNormalizer.kt`: add `normalizeLoose` and `looseKeys`.
- `data/.../epg/EpgResolutionEngine.kt`: build a loose-name index per source and add step 3b (after exact name, before provider-native).
- `domain/.../model/EpgModels.kt`: add `EpgMatchType.NORMALIZED_NAME_LOOSE` (stored as text; no schema change; `EntityMappers.kt:767` already tolerates unknown values).
- `data/.../repository/EpgSourceRepositoryImpl.kt`: per-source minimum refresh gap, 12 hours for preset-owned sources.
- `data/.../di/` (new binding module or the existing `SettingsDataModule.kt`): bind the manager and the store.
- `feature/settings/.../SettingsEpgSection.kt`, `SettingsEpgActions.kt`, the settings state and view model: "Guide presets" section with an on/off row, status line, address edit.
- `feature/settings/src/main/res/values/strings.xml` and the 38 locale folders: new strings (about 14 keys).
- `feature/live/.../sports/SportsChannelFallbackMatcher.kt`: `EVENT_ONLY_LEAGUE` reason.
- `feature/live/.../sports/SportsMetadataRegistry.kt`: league aliases for NFL and MLB only if missing (MLS is already registered at line 165; NFL and MLB were not confirmed).

Not touched: database schema, backup format, Fire TV or Google TV specific code, the guide grid.

## 4. Interfaces and data flow

```kotlin
// domain
data class EpgPresetSource(val key: String, val url: String)
data class EpgPreset(val id: String, val nameRes: String, val coverageRes: String, val sources: List<EpgPresetSource>)
object EpgPresetCatalog { val all: List<EpgPreset> }            // bein, egypt, us

data class EpgPresetStatus(
    val presetId: String, val enabled: Boolean,
    val lastSuccessAt: Long?, val lastError: String?, val refreshing: Boolean,
)
interface EpgPresetManager {
    fun observe(): Flow<List<EpgPresetStatus>>
    suspend fun setEnabled(presetId: String, enabled: Boolean): Result<Unit>
    suspend fun refreshNow(presetId: String): Result<Unit>        // refused inside the 12 h gap
    suspend fun reconcile()                                        // repair drift, assign new providers
}
```

```kotlin
// data
object EpgNameNormalizer {
    fun normalize(name: String): String                            // unchanged
    fun normalizeLoose(name: String): String                       // strips [..] (..) tags, "XX:" / "XX |" prefixes,
                                                                   // quality words, then normalize(); +1 style shifts are KEPT
    fun looseKeys(displayName: String, xmltvId: String): Set<String>  // from display name and id (dots -> spaces, ".us2" dropped)
}
```

Flow on enable:
1. `setEnabled(id, true)` reads the preset, then for each source address: reuse the existing source row if the store already maps it, else `EpgSourceRepository.addSource(name, url)`.
2. `assignSourceToProvider(providerId, sourceId, priority = max existing + 1)` for every provider.
3. `refreshSource(sourceId)` (first attempt always allowed, recorded in the store), then `resolveForProvider(providerId)`.
4. Resolution per channel (existing order, one new step): manual override, exact id, exact normalized name, **loose name (new, `NORMALIZED_NAME_LOOSE`, confidence 0.6)**, provider-native, unresolved.
5. Tie-break when several guide entries share a loose key: prefer the entry whose strict normalized name is closest in quality words, then the lexicographically smallest guide id. Deterministic and stable across refreshes (FR-012).
6. Refresh: `ProviderEpgSyncExecutor` still calls `refreshAllForProvider` hourly; preset-owned sources return early inside the 12-hour gap, so a failed or healthy source is attempted at most twice per 24 hours (FR-007).
7. Sports: when an event has a league alias and a channel's tokens include "event" and "only" plus a league alias token, `scoreCandidate` adds `EVENT_ONLY_LEAGUE` worth the minimum match score (70), so these channels appear as candidates (limit stays 8).

## 5. Edge cases and failure modes

| Case (spec) | Behavior |
|---|---|
| Source unreachable, renamed, or too large (FR-019, SC-010) | Existing refresh error path keeps the last imported rows and records the error; the status line shows it; no retry inside the 12 h gap. |
| Two presets share an address | One source row, reference-counted by the store; off for one keeps it for the other. |
| Preset on while a source was deleted by hand | `reconcile()` recreates it on the next refresh or screen open. |
| User edits a preset address (FR-006) | Uses the existing source edit; the store keeps the same source id, so the cap and state follow the source. |
| New provider added later | `reconcile()` assigns enabled presets' sources to it. |
| `+1` or other time-shift channels | **Design change vs FR-010**: time-shift labels are NOT stripped, because a "+1" channel matched to the base channel would show wrong times. Needs spec wording fix (see section 8). |
| East, West, Pacific, HD, SD variants | Deterministic tie-break (section 4, step 5). |
| Fire TV Stick with about 1.5 GB RAM | Streaming parse already exists; epgshare01 per-country files only (US2 is 6.7 MB gz). SC-006 verified on the device before release. |
| Existing matches (SC-003) | Fallback only runs when exact id and exact name both fail, so earlier matches never change; regression test compares before and after. |
| Event-only channel carries a different match (spec out of scope) | All "Event Only" league channels are offered; the app does not pick one. |
| Al Ahly guide | Only AE1 is bundled for Egypt (EG1 was last updated in 2024). If AE1 has no Al Ahly entry the channel stays empty; Al Ahly matches still list in the Sports tab through the beIN programme titles and the team-name matcher. |
| Backup and restore | Sources restore as plain sources; preset on/off state is device-local and resets. Acceptable for FR-026. |

## 6. Test plan

Unit (JVM):
- `EpgNameNormalizerLooseTest`: tags (`Fox [US]`, `USA Network HD [US]`, `Ex-Yu | Hr: Arena Sport 1`), prefixes, quality words, accents, `+1` kept, empty strings, ids like `Freeform.HD.us2`.
- `EpgResolutionEngineLooseMatchTest`: exact wins over loose; loose fills only unresolved channels; deterministic tie-break; manual override wins; **regression fixture: all channels matched before are matched to the same entries after (SC-003)**.
- `EpgPresetManagerImplTest`: enable creates and assigns; shared address ref-count; disable removes only unowned sources; reconcile after manual deletion and after a new provider; refusal of `refreshNow` inside the gap.
- `EpgSourceRepositoryPresetRefreshCapTest`: preset source attempted at most twice in a 24 h simulated clock including failures; hand-added source keeps the 5-minute rule (FR-022).
- `SportsChannelFallbackMatcherEventOnlyTest`: "MLS LIVE 1 | Event Only" offered for an MLS event; not offered for an NBA event; ordering and limit unchanged.
- `EpgPresetCatalogTest`: unique ids and addresses, all addresses accepted by `UrlSecurityPolicy.validateOptionalEpgUrl`.
- `SettingsEpgPresetViewModelTest`: state mapping, error and refreshing text, off by default (FR-002).

Localization:
- `SettingsStringsLocaleParityTest`: every new key exists, with the same placeholders, in the default file and all 38 locale folders (FR-021, SC-009). No such parity test exists today, so this is new.

Instrumented and device (Google TV and Fire TV, `AGENTS.md` procedure for anything touching live playback is not triggered because playback is unchanged):
- Enable beIN, Egypt, US in turn; count channels with a current program against SC-001 and SC-002 using the same database query used for the baseline.
- Guide scroll with all presets on, 3 passes, against SC-007 (needs the pending guide A/B result first).
- Fire TV Stick import of all three presets without the app closing (SC-006).
- Sports tab check for Inter Miami, San Diego FC, NFL, Padres (SC-008) and report any league the schedule data lacks (FR-017a).

Gate before anything is called done: full unit suites for domain, data, feature:settings, feature:live; lint; uncached debug build.

## 7. Agent recommendation

This needs ~4 agents (complexity: high), each in its own worktree from `perf/guide-and-data`, run with `--no-build-cache`:
1. **Data and domain**: catalog, store, manager, loose normalizer, engine step, refresh cap, tests. Owns `domain/`, `data/`.
2. **Settings UI**: presets section, view model state, UI tests. Owns `feature/settings` Kotlin files. Starts after agent 1 publishes the interface (can code against it from this plan).
3. **Sports matcher**: event-only rule, NFL/MLB aliases if missing, tests. Owns `feature/live/.../sports/`. Fully independent.
4. **Translations and parity test**: strings in 38 locales after agent 2 fixes the key list, plus the parity test. Owns `res/values-*/strings.xml` and the new test.

Serial parts (single Google TV and Fire TV): device validation and the SC checks stay with Claude Code after the four agents finish and one integration pass.

## 8. Points that need Andrew's decision

1. **Time-shift labels (spec FR-010):** recommend amending the spec so `+1` labels are kept, not ignored.
2. **Egypt preset coverage:** AE1 only; Al Ahly's own channels may stay without a guide even though their matches appear in the Sports tab.
3. **Order of work:** the guide A/B result should be known before SC-007 is judged.
