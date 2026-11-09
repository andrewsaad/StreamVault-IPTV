# Whole-repo review findings (W1 to W44)

Source: the whole-repo architecture review of 2026-11-07, then checked against the code on branch
`feat/epg-presets` on 2026-11-08 by read-only agents. Line numbers drift; search by name. Severity is the
agent's or Claude Code's judgement after reading the code, not a measurement. "Open" means the fault was
found in the code and no fix was found; nothing here was reproduced on a device unless a line says so.

GitHub Issues are disabled on the fork, so this file is the tracker until they are turned on.

## Fixed on this branch

| ID | Finding | Fix |
|---|---|---|
| W2 | Sync stays blocked after leaving the player: `Media3PlayerEngine.resetForReuse` never called `AutomaticSyncActivity.stopped` | commit 650c0022, test `Media3PlayerEngineResetForReuseSyncTest` |
| W3 | Pressing Home while a stream is buffering does not pause it (`PlayerLifecycleActions` paused only when `isPlaying`) | commit 441c80f1, test `PlayerLifecycleBackgroundPauseTest` |

## Blockers (open)

| ID | Finding | Evidence | Size |
|---|---|---|---|
| W1 | Guide jumps back when paging: `LiveGuideGrid` re-scrolls and re-focuses whenever `channels.size` changes; more rows are requested at `size - 15` | `LiveGuideGrid.kt:73-78`, `:118-119`. Possible cause of the guide scroll jank (SC-007, about 7% janky frames) | M |
| W4 | Launcher cards lose their content: the Parcelable extra is put on the intent and then dropped by `toUri` | `WatchNextManager.kt:167-179`. Needs a device test | M |

## Should fix (open)

| ID | Finding | Evidence | Size |
|---|---|---|---|
| W5 | M3U favorite keys include the provider id | `M3uSourceIdentity.kt:46,48`; used at `SyncManagerM3uImporter.kt:212-213`. The agent would downgrade it; the delete path was not traced | M |
| W6 | Channel search does nothing on Combined "All" and virtual lists | `HomeViewModel.kt:755-779` | M |
| W7 | Maintenance hard-deletes orphaned favorites | `Daos.kt` (about line 3580) | S |
| W8 | Providers untick is overridden on restore | `BackupManagerImpl.kt:4960-4961` (clause added in 58b8c134) | S |
| W9 | Favorites apply is not atomic with its commit | `FavoriteSyncEngine.kt:161-168`; `applier.apply` runs after the transaction | M |
| W12 | Stale provider row written back by the sync worker; only `isActive` is protected | `ProviderSyncWorker.kt:92-100` | M |
| W14 | Timeshift snapshot can crash the app (no try/catch, supervisor scope with no handler) | `LiveTimeshiftManager.kt:548-562`, `Media3PlayerEngine.kt:251,2511` | S |
| W17 | Quality consent and decode-pressure state are cleared on any network callback. Seen on a device: a routine DHCP lease renewal at 13:19 on 2026-11-08 triggered `quality-policy reason=network-change` | `Media3PlayerEngine.kt:301-304`, `:1853-1866` | S |
| W19 | Surround upmix binary is downloaded without a checksum; notice gap | `surround-upmix/native/build.sh:20` | S |
| W24 | Reverse handoff leaks an engine: the previous engine is overwritten without release | `LivePreviewHandoffManager.kt:116-124` | S |
| W32 | Exported `MainActivity` trusts a Serializable `EXTRA_PLAYER_REQUEST` with no caller check | `AndroidManifest.xml:59-61`, `ExternalNavigationRequestParser.kt:107-117` | M |
| W33 | Release workflow can publish an unsigned APK when signing secrets are missing | `.github/workflows/release.yml:66-71` (agent would raise the severity) | S |
| W34 | CI is thin: beta builds only on push to develop, no pull request trigger. Partly wrong: `release.yml:84` runs unit tests and lint | `beta.yml:84` | M |
| W35 | Update verification is weak: SHA-256 checked only if present and taken from the same GitHub origin; no signer or package check | `AppUpdateInstaller.kt` (about line 250), `GitHubReleaseChecker.kt:162` | M |
| W37 | Plugin trust is name-based; no signature check | `PluginMessengerClient.kt:70-80` | M |
| W38 | Plugin APK download accepts http, no hash, no size cap (cleartext may be blocked by the manifest) | `StreamVaultPluginManager.kt:339-352` | S |
| W39 | A package broadcast can delete providers (`queryIntentServices` without `MATCH_DISABLED_COMPONENTS` feeds the orphan cleanup) | `StreamVaultPluginManager.kt:1014-1026`, `:233-243` | S |
| W42 | R8 rules miss JSON classes such as `ProviderConfigRevisionEnvelope` | `ProviderConfigRevisionCodec.kt:12`, `app/proguard-rules.pro` | S |

## Nits (open)

| ID | Finding | Evidence |
|---|---|---|
| W10 | New HTTP client per favorites sync session | `FavoriteSyncTransport.kt:55`, `:138` |
| W11 | Trakt request mutex held across blocking I/O (also acts as a rate limiter) | `TraktApiClient.kt:512-531` |
| W13 | Oversized classes (BackupManagerImpl 6,977 lines; SettingsViewModel 1,789; Home 2,151; Epg 2,201) | |
| W16 | Memory timeshift capped by time only | `LiveTimeshiftModels.kt:12-20` |
| W18 | Support snapshot written by idle engines | `Media3PlayerEngine.kt:698-704` |
| W20 | Encoder failure after open stops playback (partly mitigated) | `UpmixAudioSink.kt:60`, `Ac3Encoder.kt:20,25` |
| W21 | Fixed 200 ms lip-sync offset | `UpmixAudioSink.kt:154` |
| W22 | Journal on external storage (risk only below API 30) | `PlaybackJournal.kt:55` |
| W31 | Guide prefetch failure can rethrow mid-flight (partly valid) | `EpgViewModel.kt:607-610` |
| W40 | Backup copy runs on the main thread (16 MiB cap bounds it) | `BackupFileBridge.kt:288-313` |
| W41 | TV catalog sync loads every channel at once | `TvInputChannelSyncManager.kt:60`, `:131-145` |
| W44 | Doc drift: `docs/DATABASE_MIGRATION_INVARIANTS.md` says schema v86; the database is at v92 | `StreamVaultDatabase.kt` |

## Not verified

- W36 (wrong update offers) was not traced in depth.
- W15, W23, W25, W26, W27 (a near-clone of two ViewModels, not diffed), W28, W29, W30 and W43 were not checked.
- W20 to W22 and W40 to W41 are low-impact and untested on a device.

## Next fixes decided (2026-11-09)

W33, W4 and W1, one commit and one test each. W1 is also the first suspect for the guide scroll jank.
