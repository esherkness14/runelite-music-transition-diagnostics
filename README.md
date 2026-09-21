# Music Transition Diagnostics

Music Transition Diagnostics is a temporary, read-only RuneLite development
plugin for inspecting what the OSRS client exposes during two different music
transitions:

- an area-triggered transition caused by crossing a music-area boundary; and
- a natural transition after the current track reaches its end.

It does **not** fade music, change volume, select tracks, stop playback, run
scripts, or contain a table of RuneScape music regions. The plugin only observes
client state and logs changes. Confirmed observations and current hypotheses are
kept in [`RESEARCH-NOTES.md`](RESEARCH-NOTES.md).

## What is recorded

The plugin listens for `VarbitChanged` involving these generated gameval varp
IDs:

- `VarPlayerID.MUSICPLAY`
- `VarPlayerID.MUSICLOOP`
- `VarPlayerID.MUSICMULTI_1`
- `VarPlayerID.MUSICMULTI_2`
- `VarPlayerID.MUSIC_CURRENT_TRACK`
- `VarPlayerID.MUSIC_LAST_TRACK`
- `VarPlayerID.MUSIC_OVERRIDE_TRACK`
- `VarPlayerID.MUSIC_OVERRIDE_AREA`
- `VarPlayerID.MUSIC_PLAYER_COLOUR_PLAYING`
- `VarPlayerID.MUSIC_CURRENT_ID`
- `VarPlayerID.MUSIC_CURRENT_ID_REMEMBERED`
- `VarPlayerID.MUSIC_NEXT_ID_REMEMBERED`

It also watches these client-side integer variables through
`VarClientIntChanged`:

- `VarClientID.MUSIC_CLIENT_SYNC_TIMER_LAST_INTERVAL`
- `VarClientID.MUSIC_CLIENT_SYNC_TIMER_TIME_PER_INTERVAL`

It samples all of the above values on every `ClientTick`, together with:

- the text of `InterfaceID.Music.NOW_PLAYING`; and
- `client.getMusicVolume()`.

The first client-tick sample emits one `BASELINE` line containing every observed
music value. After that, a polling log entry is emitted only for a value that
actually changed. Relevant `VarbitChanged` events are logged immediately with
`varpId`, `varbitId`, and the new event value. Relevant `VarClientIntChanged`
events are logged immediately with their index and current value.

Every diagnostic line includes an ISO-8601 UTC timestamp with millisecond
precision, RuneLite tick count, player `WorldPoint`, region ID, event/change
type, and an old-to-new value when the line represents a sampled change.

## Bounded script context

The plugin observes `ScriptPreFired` and `ScriptPostFired`, but it does not dump
them continuously. Instead, it keeps only a 500 ms, 256-entry rolling buffer in
memory. When `MUSIC_CURRENT_TRACK` changes, the plugin:

1. emits `SCRIPT_CONTEXT_BEFORE_BEGIN`;
2. dumps the buffered script IDs under `SCRIPT_CONTEXT_BEFORE`;
3. emits `SCRIPT_CONTEXT_BEFORE_END` and `SCRIPT_CONTEXT_AFTER_BEGIN`;
4. logs new script events under `SCRIPT_CONTEXT_AFTER` for about 500 ms; and
5. emits `SCRIPT_CONTEXT_AFTER_END`.

Every context line identifies the old and new track values. Individual script
lines contain phase (`PRE` or `POST`), script ID, original timestamp, elapsed
milliseconds relative to the detected track change, and the original RuneLite
tick count. The after-window is capped at 256 emitted events and reports whether
it was truncated.

No script arguments, stacks, or return values are inspected. The purpose is to
find a repeatable script immediately before an area-triggered replacement; a
candidate script can be investigated separately in a later experiment.

## Run the development client

This project follows the current official `runelite/example-plugin` layout and
uses Java 11 bytecode compatibility. From the repository root on Windows:

```powershell
.\gradlew.bat run
```

On macOS or Linux:

```bash
./gradlew run
```

The `run` task starts RuneLite with developer mode and debug logging enabled.
If you use a Jagex Account, follow RuneLite's current
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts)
instructions to sign in to a development client.

Enable **Music Transition Diagnostics** in RuneLite's plugin list if it is not
already enabled.

## Experiment

Use one continuous client session so the timestamps and tick counts are easy to
compare:

1. Run the development client.
2. Cross the Draynor Manor gate music boundary and note the track change plus
   its `SCRIPT_CONTEXT_BEFORE` and `SCRIPT_CONTEXT_AFTER` sections.
3. Cross back over the same boundary and compare the script IDs and ordering.
4. Separately, stand still until a song ends naturally and the next track is
   selected.
5. Compare the script context and the ordering of varp, varclient, widget-text,
   and volume changes for boundary-triggered transitions versus natural
   transitions.
6. Record confirmed results and revised hypotheses in `RESEARCH-NOTES.md`.

No music region is built into the plugin. The Draynor Manor test already showed
that a music boundary can occur within a single RuneScape region, so region IDs
are not treated as a geographic fallback.

## Logs

Look for lines beginning with `[Music Transition Diagnostics]` in both:

- the terminal or IDE console that launched the Gradle `run` task; and
- RuneLite's `client.log` under `%USERPROFILE%\.runelite\logs` on Windows or
  `$HOME/.runelite/logs` on macOS/Linux.

Example shapes (illustrative values only):

```text
[Music Transition Diagnostics] timestamp=2026-09-21T18:42:03.127Z tick=12345 worldPoint=WorldPoint(x=..., y=..., plane=0) regionId=... type=VARP_MUSIC_CURRENT_TRACK old=... -> new=...
[Music Transition Diagnostics] timestamp=2026-09-21T18:42:03.128Z tick=12345 worldPoint=WorldPoint(x=..., y=..., plane=0) regionId=... type=SCRIPT_CONTEXT_BEFORE trackOld=... trackNew=... phase=PRE scriptId=... scriptTimestamp=... offsetMs=-12.345 scriptTick=12345
```

## Build and test

```powershell
.\gradlew.bat clean build
```

The test source set contains the standard development-client launcher used by
the `run` task. There are no behavioral unit tests because the experiment
depends on live client events and a player manually crossing a boundary or
waiting for a track to finish.
