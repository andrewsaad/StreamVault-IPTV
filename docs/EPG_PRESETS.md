# Guide presets

Guide presets are ready-made program guide sources you can turn on in Settings under Guide presets. They fill the guide and the Sports tab for channels whose provider supplies no guide data of its own.

## Presets

| Preset | Covers | Guide files, in priority order |
|---|---|---|
| `bein` (beIN Sports, Arabic) | beIN Sports channels and Arabic networks | open-epg `qatar1.xml`, then `epg_ripper_BEIN1.xml.gz`, `epg_ripper_AE1.xml.gz` |
| `egypt` (Egypt) | Al Ahly and Egyptian sports channels | `epg_ripper_AE1.xml.gz` |
| `us` (US sports and networks) | NFL, MLS, MLB and national US channels | `epg_ripper_US2.xml.gz`, `epg_ripper_US_SPORTS1.xml.gz` |

The epgshare01 files come from `https://epgshare01.online/epgshare01/`, and the Qatar file from `https://www.open-epg.com/files/`. Qatar comes first for beIN because BEIN1's channel ids do not match provider beIN names and its times look about 2 hours late (`specs/001-epg-presets-matches/validation-precision.md`). When two presets use the same file (AE1), the app keeps one source and shares it. Turning one preset off keeps the source while the other preset still needs it.

## Behavior

- Every preset is OFF by default. Nothing is downloaded until you turn a preset on.
- The on or off state is per device. Turning a preset on for the Google TV does not turn it on for the Fire TV, and the other way round. Backups do not carry the state.
- Turning a preset off removes its guide data and stops updating it.
- Presets never replace hand-added guide sources. Existing exact matches and manual matches stay as they are.
- If a source cannot be reached, the last good guide data stays visible and the preset shows the failure and the time of the last successful update.

## Terms of use

epgshare01 is a public community site run by a volunteer. Its terms of use are unclear and there is no service guarantee. The presets are opt-in for that reason. The app reads no preset list from any other online location.

## Update limit

A preset source waits at least 12 hours between attempts, failed attempts included. That means at most 2 refreshes in any 24 hours per device. "Update now" is refused inside the 12-hour gap. Guide sources you add by hand keep their existing 5-minute rule.

## Editing a preset's address

If a source moves or is renamed, the update fails and the preset shows the error. Open Settings, Guide presets, choose the preset and select Change address, then enter the corrected address. The change applies to this device only. A corrected default address for everyone arrives only through a new app build.

## How channels are matched

For each channel the app tries these steps in order and stops at the first match:

1. Manual overrides always win.
2. Exact id and exact name matches. A match made this way is never replaced.
3. Loose name match, only when no exact match exists. The loose name ignores:
   - country or region tags such as `[US]`, `(AR)` or a `AR:` / `AR |` prefix
   - quality words such as HD, FHD, UHD, 4K, H265, SD, Low, Backup
   - separators and accents
   - a trailing "TV" word
4. "sports" and "sport" are treated as the same word, so "beIN Sports 1" equals "beIN Sport 1".
5. Time-shift labels such as `+1` are kept. A `+1` channel matches only a guide entry with the same `+1` label, never the base channel.

If several guide entries match after the loose step, the same one is picked every time for that channel.

Example: "AR: beIN Sport 1 H265 [SD]" matches the guide entry "beIN SPORTS 1".

## Not covered

- No public source exists for Azerbaijan, Armenian, Iran and Kurdish channels. Their guide rows stay empty.
- Coverage is weak for Albania (about 35%) and Russia (about 25%).
- Local and regional US channels that the national files do not list stay empty.
- A channel with no guide data shows an empty row, never an error.

## Sports tab: event-only channels

Channels named like "MLS LIVE 3 | Event Only" carry whichever match of their league is on, and the app cannot tell which one. For a match in that league, the Sports tab offers all matching event-only channels as candidates, ranked below channels that carry the teams by name. They are not offered for matches in other leagues. A match with no candidate shows a "no channel available" label.
