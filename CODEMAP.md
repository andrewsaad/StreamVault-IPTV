<!-- built-at: 82fa1b94955c3a03a00e24b4fc14855c354b472a -->
<!-- updated: 2026-11-09 -->
# CODEMAP — StreamVault IPTV

## Overview
- Android TV / mobile IPTV player (Kotlin, Jetpack Compose, Hilt, Room, Media3/ExoPlayer). Live TV, EPG, VOD, series, recordings, sports, Trakt, Stremio.
- Providers: Xtream, M3U, Stalker, Jellyfin; plus a home "sync server" (now-playing board, favorites sync).
- One APK (`com.streamvault.app`), no product flavors. Fire TV and Google TV share one flow (Fire TV is only detected as "a TV" in `core/ui/.../device/TelevisionDevice.kt`).

## Modules (settings.gradle.kts) and allowed dependencies
- `:domain` — pure models, repository interfaces, use cases. Depends on nothing.
- `:data` → `:domain` — Room (`local/`), repositories (`repository/`), providers + remote APIs (`provider/`, `remote/xtream|jellyfin|...`), EPG import (`epg/`, `parser/`), sync workers (`sync/`, incl. `sync/nowplaying/`), DataStore prefs (`preferences/`), Trakt (`trakt/`), sports (`sports/`).
- `:player` → `:domain`, `:surround-upmix` — `Media3PlayerEngine.kt`, load control, stats, timeshift, tracks, preload (`PlayerPreload.kt`).
- `:surround-upmix` — audio upmix sink. No deps.
- `:core:ui` — shared Compose components, TV focus/interaction, device detection, image helpers (`image/ChannelLogoBadge.kt`). No deps.
- `:core:navigation` — routes. No deps.
- `:feature:live` (→ ui, nav, data, domain, player) — live guide/EPG grid (`epg/`), home, sports.
- `:feature:playback` (→ ui, nav, domain, player) — player screen, zap/recovery/prep coordinators (`player/`).
- `:feature:catalog` (→ ui, nav, data, domain) — VOD/series browse + detail.
- `:feature:provider`, `:feature:settings` (+player), `:feature:system`, `:feature:stremio` (→ ui, nav, domain).
- `:app` → every module — Hilt wiring (`di/`), Application, manifest, TV input, update, backup, cast, services.
- `:benchmark` — macrobenchmark / baseline profile generation.

## Entry points
- `app/.../StreamVaultApp.kt` — Application.onCreate: Hilt, crash store, Coil, starts `DatabaseStartupCoordinator`, `AppStartupCoordinator.kt` (deferred startup tasks, `StartupWorkRegistry.kt`).
- `app/.../MainActivity.kt` — single Compose activity, app navigation host.
- Manifest components: `.trakt.TraktCallbackActivity`, `.cast.CastRouteChooserActivity`, `.tvinput.TvInputSetupActivity` + `StreamVaultTvInputService` (Android TV input / launcher), `.diagnostics.ExternalPlayerProbeActivity`, `.service.DownloadForegroundService`, `com.streamvault.torrent.TorrentPlaybackService`, FileProvider.
- Background: `data/.../sync/ProviderSyncWorker.kt` (WorkManager provider sync; queue names in `ProviderWorkScheduling.kt`).

## Data flow
- Provider config → `data/provider` + `remote/<type>` fetch → parsers (`data/parser`, `epg/XmltvParser`) → Room DAOs (`data/local/dao/Daos.kt`, entities in `Entities.kt`) → repositories (`data/repository`) implement `domain/repository` interfaces → ViewModels in `feature/*` → Compose screens.
- Playback: UI zap/select → `feature/playback/.../PlayerZapActions.kt` → stream resolve (`XtreamStreamUrlResolver` etc.) → `PlayerPreparationCoordinator.kt` → `player/Media3PlayerEngine.kt` (prepare/recreate/preload) → recovery in `PlayerRecoveryActions.kt`.
- EPG: XMLTV/Xtream → `EpgRepositoryImpl.kt` batched insert → `PlayerEpgCoordinator.kt` (30 s refresh) and `feature/live/.../epg/LiveGuide*.kt` grid.
- EPG presets: `EpgPresetManagerImpl.kt` (domain `EpgPresetCatalog`: bein/egypt/us) turns a preset on by creating ordinary rows in `epg_sources` and assigning them to providers; on/off + last-attempt state is device-local in `EpgPresetStateStore.kt` (DataStore, not backed up); the loose-name step (`EpgNameNormalizer.normalizeLoose`) runs in the EPG resolution engine only after exact matches; preset sources have a 12 h refresh gap in `EpgSourceRepositoryImpl.kt`. See `docs/EPG_PRESETS.md`.
- Channel clean-up: Settings > Providers > Channel clean-up (`feature/settings/.../ChannelCleanupDialog.kt` + `ChannelCleanupViewModel.kt`) → domain `ChannelHealthRepository` → `data/.../health/ChannelHealthScanWorker.kt` (WorkManager) → `ChannelHealthScanner.kt` (one channel at a time, 6 s pace, yields to playback via `AutomaticSyncActivity`, retries 5xx once) → `ChannelHealthProbe.kt` → results JSON in DataStore (`channel_health_<providerId>`, runtime key, not backed up) → suggestions need 2 failed days → hide via the hidden-channels list (`PreferencesRepository.addHiddenChannelIds`).
- Household sync: TVs → `SyncServerNowPlayingRegistry.kt` (2-minute refresh) → now-playing board; favorites/custom groups via `FavoriteSyncModule`.
- Settings: `data/preferences` (DataStore) ↔ `domain/settings`.

## Config surface (names only, never values)
- `local.properties` (gitignored; template `local.properties.example`): `xtream.dev.*`, `m3u.dev.*`, OpenSubtitles key — read into debug `BuildConfig` only.
- Env: `OPENSUBTITLES_API_KEY` (CI beta/release). Trakt client id/redirect via Gradle (`TRAKT_CLIENT_ID`, `TRAKT_REDIRECT_URI`).
- `BuildConfig`: `APP_UPDATE_CHANNEL`, `OFFICIAL_APPLICATION_ID`, `OFFICIAL_SIGNING_CERT_SHA256`, `XTREAM_DEV_*`, `M3U_DEV_*`.
- Build types: debug, release (minify + shrink on), beta and benchmark (minify off). R8 rules `app/proguard-rules.pro`; baseline profile `app/src/main/generated/baselineProfiles/baseline-prof.txt`.
- EPG presets: catalog (ids, epgshare01 addresses) in `domain/.../model/EpgPreset.kt`; per-device state in the `EpgPresetStateStore` DataStore; the sources themselves live in the `epg_sources` table (user-editable address).
- Provider account allows `max_connections` (1 on the dev provider): scans and playback share it; PC probe scripts and the login live outside the repo (`secret.md` is ignored).
- Locales: `app/src/main/res/xml/locales_config.xml` (every UI string needs all locales, see AGENTS.md).
- Secrets files ignored by git: `local.properties`, `keystore.properties`, `secrets.properties`, `secret.properties`, `keystore/`, `secret.md`.
- Docs: `docs/` (CHANGELOG.md, PLUGIN_API.md, sports/, DATABASE_MIGRATION_INVARIANTS.md). Tools/fixtures: `tools/` (adb/fixture scripts), lint baseline `app/lint-baseline.xml`.

## Boundaries
- `:domain` has no Android or other-module dependencies; `:data` and `:player` must not depend on any `:feature:*`.
- `feature:*` modules must not depend on each other; shared UI lives in `:core:ui`, routes in `:core:navigation`.
- Only `:app` wires Hilt bindings across modules (`app/.../di/*`); `:data`/`:player` expose their own DI modules.
- Room schema changes need a migration and tests (`docs/DATABASE_MIGRATION_INVARIANTS.md`).
- User-facing text only in module `res/values*/strings.xml` (AGENTS.md localization rule).
- Live TV playback changes need the AGENTS.md screenshot + logcat validation.
- Launcher cards: `PlayerRequestIntentExtras.kt` (plain extras) accepts only internal provider URLs (xtream://, Stalker); `MainActivity` is exported, so intent extras are untrusted input.

Exhaustive per-file map: CODEMAP_FULL.jsonl, keyed by path — look up with `node ~/.claude/scripts/doc-lookup.js CODEMAP_FULL.jsonl <path>`, never read the whole file.
