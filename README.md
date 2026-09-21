# Music Transition Diagnostics

Music Transition Diagnostics is a temporary, read-only RuneLite development
plugin for inspecting what the OSRS client exposes during two different music
transitions:

- an area-triggered transition caused by crossing a music-area boundary; and
- a natural transition after the current track reaches its end.

It does **not** fade music, change volume, select tracks, stop playback, or
contain a table of RuneScape music regions. The plugin only observes client
state and logs changes.

## What is recorded

The plugin listens for `VarbitChanged` involving these generated gameval varp
IDs:

- `VarPlayerID.MUSICPLAY`
- `VarPlayerID.MUSICLOOP`
- `VarPlayerID.MUSICMULTI_1`
- `VarPlayerID.MUSICMULTI_2`

It also samples the same four varps on every `ClientTick`, together with:

- the text of `InterfaceID.Music.NOW_PLAYING`; and
- `client.getMusicVolume()`.

The first client-tick sample establishes a silent baseline. After that, a
polling log entry is emitted only for a value that actually changed. Relevant
`VarbitChanged` events are logged immediately with `varpId`, `varbitId`, and
the new event value.

Every diagnostic line includes an ISO-8601 UTC timestamp with millisecond
precision, RuneLite tick count, player `WorldPoint`, region ID, event/change
type, and an old-to-new value when the line represents a sampled change.

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
2. Cross a known music-area boundary and note the resulting log lines.
3. Cross back over the same boundary and note the resulting log lines.
4. Separately, stand still until a song ends naturally and the next track is
   selected.
5. Compare the ordering and timestamps of the varp, widget-text, and volume
   changes for boundary-triggered transitions versus natural transitions.

No music region is built into the plugin; choose any boundary you already know
for the manual experiment.

## Logs

Look for lines beginning with `[Music Transition Diagnostics]` in both:

- the terminal or IDE console that launched the Gradle `run` task; and
- RuneLite's `client.log` under `%USERPROFILE%\.runelite\logs` on Windows or
  `$HOME/.runelite/logs` on macOS/Linux.

Example shape (illustrative values only):

```text
[Music Transition Diagnostics] timestamp=2026-09-20T18:42:03.127Z tick=12345 worldPoint=WorldPoint(x=..., y=..., plane=0) regionId=... type=VARP_MUSICPLAY old=... -> new=...
```

## Build and test

```powershell
.\gradlew.bat clean build
```

The test source set contains the standard development-client launcher used by
the `run` task. There are no behavioral unit tests because the experiment
depends on live client events and a player manually crossing a boundary or
waiting for a track to finish.

