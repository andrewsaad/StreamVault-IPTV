# Loose match precision (F7), 2026-11-07

The real resolution engine at commit c853d190 was run offline over the provider's 5,537 channel names (from the Kitchen Fire TV database) and the guide channels of each preset. Sample: 50 loose matches per preset, seed 20261107. No TV was involved.

## Result

| Preset run | Channels mapped | Loose | Sample | Right channel by name | Unclear | Wrong |
|---|---|---|---|---|---|---|
| beIN (BEIN1, AE1) | 403 | 352 | not sampled, see below | | | |
| beIN plus Qatar (qatar1, BEIN1, AE1) | 548 | 497 | 50 | 50 | 0 | 0 |
| Egypt (AE1) | 396 | 345 | 50 | 48 | 2 | 0 |
| US (US2, US_SPORTS1) | 81 | 80 | 50 | 50 | 0 | 0 |

Unclear: `RU: OUTDOOR CHANNEL HD` to `Outdoor.Channel.ae` and `[FR]_M6` to `en:.M6.ae`. The name matches, but the guide entry may be another country's version.

## Limits

- This checks names only, by one reviewer. It does not compare programmes with what a channel airs.
- A clean sample does not mean no false matches. Two known ones remain outside the samples: `ON E [EG]` and `PH: One` both reach `One.TV.ae`, because spaces are ignored in the key.
- An earlier sample before the last F6 rules found four other false matches, now fixed: `Afg: 1 TV`, `24 HD [TR]`, `MBC Drama+ HD` and `Ex-Yu | Srb: HBO 2 HD`.

## SC-001: the beIN preset does not reach the target by itself

| Guide sources | beIN sports channels mapped (of 188) | Share |
|---|---|---|
| The catalog's beIN preset: BEIN1, AE1 | 20 | 10.6% |
| Those plus Andrew's hand-added open-epg `qatar1.xml` | 165 | 87.8% |

BEIN1 names its channels `beIN_SPORTS1_DIGITAL_Mono_AR.bein`, which does not match `beIN Sport 1`. The counts are channels mapped, not channels with a programme now, so they are upper bounds for SC-001 as written.

## BEIN1 times look 2 hours late

Same titles on the same channel, BEIN1 minus the Qatar feed:

| Channel | Most common difference | Titles at that difference |
|---|---|---|
| beIN Sports 1 | +120 min | 27 of 32 compared |
| beIN Sports 2 | +120 min | 25 of 37 compared |
| beIN Sports EN 1 | +120 min | 34 of 66 compared |

The other pairs are mostly repeat airings of a title that the comparison pairs by name, so they say little about the offset.

BEIN1 lists Valencia vs Real Madrid at `22:58 +0100` and the Qatar feed at 22:58 Qatar time, so BEIN1 appears to label Qatar local time as +0100. This is inferred by comparing the two feeds, not checked against a broadcaster schedule.
