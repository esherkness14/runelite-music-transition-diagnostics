# Music Transition Diagnostics

A temporary RuneLite development plugin comparing area-triggered music
replacement with natural end-of-track progression. The ordinary launcher is
read-only. Experiment 4 adds a separate, explicitly opt-in launcher for one
narrow incoming-fade test at the verified native request entry.

Neither mode selects tracks, changes volume/varps, runs scripts, or contains a
geographic music map. Normal `run` changes in-memory bytecode only enough to
emit diagnostics; original arguments, method bodies, returns, and exceptions
remain intact. `runAreaFadeTest` is the sole exception described below. Research
findings live in [RESEARCH-NOTES.md](RESEARCH-NOTES.md).

## Run Experiment 3

Use a JDK capable of running Gradle 8.10 (Java 11 bytecode is generated). From
the repository root:

```powershell
.\gradlew.bat run
```

On macOS/Linux use `./gradlew run`. Gradle builds and attaches
`build/libs/music-transition-core-probe-agent.jar` automatically and starts the
existing developer-mode/debug RuneLite launcher. Enable **Music Transition
Diagnostics** in the plugin list if necessary. Any account login is your own
manual step; do not share credentials or session files for this experiment.

At startup look for `[Music Transition Core Probe] ... state=ARMED` and four
`state=TRANSFORMED` messages as the target classes load. `ARMED` means preflight
passed, not that a music request has occurred. If `state=DISABLED` appears,
RuneLite may still run normally, but do not treat that session as a successful
core-probe experiment. Preserve the reason and re-inspect the current artifact.

1. Cross the Draynor Manor gate music boundary and note the time/direction.
2. Cross back and note the time/direction again.
3. Separately stand still until a track ends naturally and another is selected.
4. Compare the request IDs, four timing arguments, route/caller, and ordering
   against varp and active-MIDI changes. `nanoTime` in core lines and `monoNanos`
   in plugin lines use the same JVM monotonic clock. Do not compare them across
   JVM runs or assume the timing arguments are milliseconds.
5. Record results in `RESEARCH-NOTES.md`.

The completed live run found `0,60,60,0` in both Draynor directions through
the packet / `client.ia` path. Natural progression first requested archive 147
with `0,20,0,0`, then about 1.18 seconds later archive 151 with `0,0,0,0`.
Archive 147's content/role is unknown and is not labeled as silence.

## Run Experiment 4 (opt-in behavior change)

```powershell
.\gradlew.bat runAreaFadeTest
```

On macOS/Linux use `./gradlew runAreaFadeTest`. This is not the normal launcher.
It retains all Experiment 3 logging and changes `ij.af` only when:

- `specialRoute == false`, and
- the complete original tuple is exactly `0,60,60,0`.

For that exact match only, the effective tuple becomes `0,60,60,60`: outgoing
delay/fade and incoming delay are preserved, and only incoming fade changes.
The probe logs both `originalTimings=[0,60,60,0]` and
`effectiveTimings=[0,60,60,60]`. Natural tuples `0,20,0,0` and `0,0,0,0`,
special/jingle requests, and unrelated timings are untouched.

This signature match is a controlled experiment based on one observed boundary,
not the final plugin's area-detection design. It adds no silence gap and does not
change the outgoing fade. The user must perform any RuneScape login/boundary test
manually; the build/tests do not launch or live-test the game.

No location is hard-coded in the plugin. Region IDs are metadata only; the
Draynor boundary was already observed within a single RuneScape region.

## Native core probe

The exact verified 1.12.39 method entries are:

| Method | JVM descriptor | Recorded payload |
| --- | --- | --- |
| `rj.bc(ArrayList, int, int, int, int, byte)` | `(Ljava/util/ArrayList;IIIIB)V` | Copied requested IDs and four timings |
| `ij.af(ArrayList, int, int, int, int, boolean, int)` | `(Ljava/util/ArrayList;IIIIZI)V` | Request count, four timings, `specialRoute` |
| `bk.ab(int, int, byte)` | `(IIB)V` | Outgoing delay/fade and raw `arg3` |
| `im.as(int, int, int, int, int)` | `(IIIII)V` | Raw `arg1` through `arg5` |

Each invocation emits one concise line with a millisecond UTC timestamp,
`System.nanoTime()`, method, and immediate caller. Caller classification is
strict: only `client.ia`/`client.mh` are `PACKET`, only `ee.bt` is `SCRIPT`, and
all other callers are `OTHER`. For example `ij.af` called by `rj.bc` is `OTHER`;
this is intentionally not an inferred upstream packet category. No full stacks,
script arguments, or arbitrary packet payloads are dumped. `ij.af` logs count
only, avoiding reflection on its internal request objects.

The agent verifies the complete official injected-client 1.12.39 SHA-256 and
all four static signatures before registering a transformer. At load time it
checks classloader, jar provenance, and original class bytes again. Missing,
shadowed, changed, already-loaded, or unsupported targets disable the probe;
it does not instrument a guessed signature. The expected hash and all findings
are recorded in `RESEARCH-NOTES.md`.

The development client dependency still uses `latest.release`: a future release
may therefore disable this version-specific probe. Re-research that binary
before changing the allowlist. Other agents/retransformation/custom classloaders
are not supported. Only the four listed names are instrumented; obfuscated
alternate/copy methods are not claimed to be covered.

The Experiment 4 agent argument is also allowlisted exactly. Unknown modes fail
closed. Normal `run` attaches with no behavior-changing mode; only
`runAreaFadeTest` emits the narrowly scoped incoming-fade substitution.

## Plugin observations

`VarbitChanged` is filtered to these current generated `VarPlayerID` constants:

- `MUSICPLAY`, `MUSICLOOP`, `MUSICMULTI_1`, `MUSICMULTI_2`
- `MUSIC_CURRENT_TRACK`, `MUSIC_LAST_TRACK`
- `MUSIC_OVERRIDE_TRACK`, `MUSIC_OVERRIDE_AREA`, `MUSIC_PLAYER_COLOUR_PLAYING`
- `MUSIC_CURRENT_ID`, `MUSIC_CURRENT_ID_REMEMBERED`, `MUSIC_NEXT_ID_REMEMBERED`

`VarClientIntChanged` observes the `VarClientID` integers
`MUSIC_CLIENT_SYNC_TIMER_LAST_INTERVAL` and
`MUSIC_CLIENT_SYNC_TIMER_TIME_PER_INTERVAL`.

Every `ClientTick` samples those values, `InterfaceID.Music.NOW_PLAYING` text,
`client.getMusicVolume()`, and `client.getActiveMidiRequests()`. Active requests
are copied to immutable `(archiveId, isJingle)` values, preserving list order;
neither the live list nor its mutable request objects are retained. A defensive
null slot is recorded as `null`.

The first tick emits one complete `BASELINE`. Later ticks log only changes,
including `ACTIVE_MIDI_REQUESTS old=... -> new=...`. Relevant varp/varclient events
are still logged immediately. Every plugin line includes UTC milliseconds,
`monoNanos`, RuneLite tick count, player WorldPoint, region ID, and event type.
Missing player/widget information is explicit.

The Experiment 2 `ScriptPreFired`/`ScriptPostFired` subscriptions and context
buffer are removed. Their findings remain in the research notes and Git history.
Active-request membership does not guarantee that a track is audible, and an
archive ID is not assumed to equal `MUSIC_CURRENT_TRACK`.

## Logs

- Core probe: stdout in the launching terminal, plus
  **`build/music-core-probe.log`**, truncated each dev-client run and flushed
  after every line. Run one development client per repository at a time.
- Plugin: `[Music Transition Diagnostics]` in the terminal and RuneLite's
  `%USERPROFILE%\.runelite\logs\client.log` on Windows
  (`$HOME/.runelite/logs/client.log` on macOS/Linux).

Core stdout is not guaranteed to be copied into RuneLite's `client.log`; use the
separate probe file. Preserve a run's probe log before launching again or running
`clean`. `build/` is already ignored by Git. Credential/session/account/token
files are never diagnostic inputs.

Illustrative shapes, not measured results:

```text
[Music Transition Core Probe] timestamp=2026-09-22T18:00:00.123Z nanoTime=123456789 method=rj.bc requestedIds=[123] outgoingDelay=0 outgoingFade=0 incomingDelay=0 incomingFade=0 caller=client.mh callerCategory=PACKET
[Music Transition Diagnostics] timestamp=2026-09-22T18:00:00.124Z monoNanos=124456789 tick=123 worldPoint=<unavailable> regionId=-1 type=ACTIVE_MIDI_REQUESTS old=[] -> new=[{archiveId=123,isJingle=false}]
```

## Build and isolation

```powershell
.\gradlew.bat build
```

The build includes plugin snapshot tests, synthetic transformer tests (including
observer-mode pass-through and all Experiment 4 match/non-match cases), real
`-javaagent`/`-Xverify:all` class-loading smoke tests for both agent modes against
the resolved client, and fail-closed startup tests. Smoke tests do not initialize the target game
classes, invoke their music methods, launch RuneLite, log in, or cross boundaries.
Test logs go under `build/test-results/`, not the live experiment file.

Agent implementation/dependencies live only in the `coreProbe` source set;
its tests live in `coreProbeTest`. Neither the ordinary plugin jar nor the
example-style `shadowJar` includes the agent or its ASM dependencies. The
`shadowJar`/IDE launcher does not automatically attach the agent: use Gradle
`run` for observation or `runAreaFadeTest` for the explicit Experiment 4 mode.
No Gradle-cache or official jar is modified in place, and nothing is submitted
upstream.
