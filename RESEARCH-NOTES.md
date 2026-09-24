# Music Transition Diagnostics research notes

This is the durable experiment log for the reverse-engineering spike. Record
observations separately from hypotheses, include enough detail to reproduce a
test, and do not promote a hypothesis to a finding without repeatable evidence.

## Experiment 1: natural progression versus an area boundary

### Purpose

Determine whether the client exposes a distinct state sequence for natural
end-of-track progression and for an area-triggered track replacement.

### Procedure

1. Run the development client with Music Transition Diagnostics enabled.
2. Cross the known music boundary at the Draynor Manor gate and cross back.
3. In a separate observation, remain stationary until the current song ends
   naturally and the next track begins.
4. Compare ordered varp changes and their timestamps/tick counts.

### Confirmed observations

#### Natural end-of-track progression

- `MUSIC_CURRENT_TRACK` changes from the active track to `-1`.
- `MUSIC_LAST_TRACK` is updated.
- Roughly 1.2 seconds later, the next track is assigned to
  `MUSIC_CURRENT_TRACK`.
- This provides an observable intermediate interval that may be useful for a
  fade/pause/fade sequence, although the earlier trigger is not yet known.

#### Draynor Manor gate boundary

- `MUSIC_CURRENT_TRACK` changes directly from the old track ID to the new track
  ID.
- No intermediate `MUSIC_CURRENT_TRACK = -1` state was observed.
- No corresponding `MUSIC_LAST_TRACK` update was observed.
- The boundary occurs within the same RuneScape region on both sides. Region
  IDs therefore cannot accurately describe this boundary and are too coarse
  for a geographic fallback.

## Initial hypotheses (before Experiment 2)

1. Natural progression and area-triggered replacement use different branches
   of the client's existing music machinery.
2. One or more client scripts may select or request the area track immediately
   before the direct `MUSIC_CURRENT_TRACK` replacement. A repeatable script ID
   could provide an earlier hook than `VarbitChanged`.
3. The newer current/remembered music ID varps may expose queue or selection
   state not visible in `MUSIC_CURRENT_TRACK` and `MUSIC_LAST_TRACK` alone.
4. The game's own transition machinery is still the best candidate for a
   general solution; a geographic map should remain a last resort.

## Experiment 2: bounded client-script correlation

### Instrumentation

- Observe `MUSIC_CURRENT_ID`, `MUSIC_CURRENT_ID_REMEMBERED`, and
  `MUSIC_NEXT_ID_REMEMBERED` alongside the existing music state.
- Keep a bounded rolling buffer of `ScriptPreFired` and `ScriptPostFired` IDs.
- On each `MUSIC_CURRENT_TRACK` transition, dump about 500 ms of preceding
  script events and log about 500 ms of following script events.
- Record phase, script ID, timestamp, relative offset, and RuneLite tick count.
- Do not record script stacks or arguments.

### Questions tested

- Does the same script ID consistently appear immediately before both
  directions of the Draynor Manor boundary crossing?
- Is that script absent from, or differently ordered during, natural
  progression?
- Do the current/remembered ID varps change earlier than
  `MUSIC_CURRENT_TRACK`?
- Is the candidate script specific to opening/updating the music interface, or
  does it plausibly participate in track selection?

### Results

Results supplied by the user from the latest diagnostic log (recorded here on
2026-09-21). The raw log was not attached to this research pass; the relative
times below are reported observations, not a new independent log analysis.

- Natural progression again showed active track -> `MUSIC_CURRENT_TRACK = -1`,
  a `MUSIC_LAST_TRACK` update, and the next track about 1.2 seconds later.
- The Draynor Manor gate area change again replaced the track directly.
- `MUSIC_CURRENT_ID`, `MUSIC_CURRENT_ID_REMEMBERED`, and
  `MUSIC_NEXT_ID_REMEMBERED` provided no useful changes in this test.
- The closest observed script completed approximately 20 ms before the area
  track change. Proximity alone does not establish a causal relationship.
- A large script burst started after the change, too late to provide advance
  notice for that replacement. It may be interface/state-change handling;
  its purpose has not been established from IDs and ordering alone.
- No repeatable pre-replacement script hook was established. Script interception
  is now a lower-priority hypothesis, not disproved for every possible request.
  Do not infer a script ID from these notes.

The initial hypothesis about the three additional ID varps was not supported
by this experiment.

## Implementation investigation: 2026-09-21

### Scope and provenance

This pass changed documentation only. It did not execute music methods, attach
hooks, change playback/volume/varps/scripts, or launch a logged-in client. No
credential, account, session, or token files were inspected.

- Inspected current official source and downloaded Gradle artifacts `client`,
  `runelite-api`, and `injected-client`, version **1.12.39**.
- The [official launcher bootstrap](https://static.runelite.net/bootstrap.json)
  currently names 1.12.39. Its injected-client SHA-256 matches the local jar:
  `25f42961c400bd9dfff1554402441c0ba6d1cffd011163cb9b0b4c42ae194f85`.
  This identifies the inspected client more precisely than `latest.release`.
- Exact binary: `net.runelite:injected-client:1.12.39`, 4,207,579 bytes;
  [official artifact](https://repo.runelite.net/net/runelite/injected-client/1.12.39/injected-client-1.12.39.jar).
  Its `client.getBuildID()` returns `35108282871.259`.
- Official source HEAD observed during inspection:
  `a5c2494fc913d5b6023edc7883c4933c948f852e`. Source inspection used the current
  master archive; binary conclusions come from the separately versioned release.
- Used JDK `jar`/`javap -p -c` and CFR 0.152 without executing game classes.
  Ignored evidence under `build/research/` includes `client.javap.txt`,
  `all.javap.txt`, `classes/`, `targeted/`, and the official source tree.
  A temporary JDK/decompiler is also there. These are disposable build artifacts,
  not files to commit; `gradlew clean` removes them.
- The jar's Gradle-cache version subdirectory is
  `net.runelite/injected-client/1.12.39/392d934db7998efec74ba7ae42768f7dd7f279e4/`.
  Reproduce with `javap -p -c -classpath <jar> client nb np rj ij if wo wp wu
  wk wn ws wl wq nu mc xq ee`. Quote `if` if necessary: it is a binary class name.

Short names below are verified for this exact jar, not stable APIs or names from
old deob articles. Role names are our descriptions unless confirmed by task-name
strings. The binary has duplicate/unused-looking methods, opaque multipliers,
retained source-file names that do not always match class names, and control
flow CFR cannot always structure. Follow actual binary owners/call instructions
and check `javap`, not every decompiled alternative.

### Confirmed public source/API findings

1. [Client.java](https://github.com/runelite/runelite/blob/a5c2494fc913d5b6023edc7883c4933c948f852e/runelite-api/src/main/java/net/runelite/api/Client.java)
   exposes `List<MidiRequest> getActiveMidiRequests()` and music-volume accessors.
2. [MidiRequest.java](https://github.com/runelite/runelite/blob/a5c2494fc913d5b6023edc7883c4933c948f852e/runelite-api/src/main/java/net/runelite/api/MidiRequest.java)
   exposes only `isJingle()` and `getArchiveId()`: not timing arguments, individual
   stream volume, loading/finished status, or the pending request list.
3. The inspected API, events, and callbacks expose no music-request-before-change
   event or transition-timing setter. The built-in Music plugin is not a fade or
   request-interception implementation.
4. The inspected public source tree/resolved jars do not supply corresponding
   named RS music-player interface/mixin sources. No usable
   `net.runelite.rs.api.RSClient` was present in the injected jar. Requests for
   public RS-API/mixin Maven metadata yielded no inspectable metadata here.
   This is a scope limitation, not proof that RuneLite's internal injection
   project lacks such mappings. The injected bytecode verifies the accessors.
5. Current generated
   [VarPlayerID.java](https://github.com/runelite/runelite/blob/a5c2494fc913d5b6023edc7883c4933c948f852e/runelite-api/src/main/java/net/runelite/api/gameval/VarPlayerID.java)
   defines `MUSIC_CURRENT_TRACK = 3883`, `MUSIC_LAST_TRACK = 3885`, and the extra
   ID varps as 2975, 2978, 2979. Names do not establish engine-trigger semantics
   or an audio-archive ID mapping.

### Confirmed compiled-client findings: requests and player state

| Exact binary location | Verified role/evidence |
| --- | --- |
| `client.getActiveMidiRequests()` | Returns `Collections.unmodifiableList(np.ab)`: a live view, not a copied snapshot. |
| `nb implements MidiRequest` | Request object: `getArchiveId()` decodes `nb.af`; `nb.ae` is file ID; `nb.al` references its `nu` stream; `nb.ay` references loaded `no` track data. |
| `nb.ab`, `nb.ag`, `nb.aa` | Target integer volume, current float fade volume, and fade-in-progress flag, as used by the fade tasks. Not the global volume preference. |
| `nb.kl(...)`, `nb.isJingle()` | Injection records whether the supplying archive index is 11 and returns that flag. |
| `np.ae`, `np.ab`, `np.ac` | Pending request linked list; active request list; remembered non-jingle request list, also used for replacement requests during jingles. |
| `np.af`, `np.aa` | Pool of `nu` streams; transition-task list. |
| `nu extends ah` | MIDI-to-PCM stream/player with channel/note state, `nc` MIDI reader, and `nd` audio substream. |
| `nu.aj(no, boolean, byte)` | Clears/initializes stream, installs MIDI data, initializes reader timing, sets loop flag. |
| `nu.az(int, int)` | Sets individual stream volume; called by fade tasks. |
| `nu.ap(nu, byte)` | Queries MIDI reader readiness under the stream lock. |

`wn.az(byte)` moves pending requests into `np.ab` and assigns streams BEFORE
loading/starting completes. Membership in the API's active list therefore does
not prove audio is already audible. Future read-only polling should copy
`(archiveId, isJingle)` values on the client thread, not retain the live list as
the previous snapshot. Do not assume archive ID equals `MUSIC_CURRENT_TRACK`
without measuring/mapping that relation.

### Confirmed compiled-client findings: request entry and timing

The main request entry point found is:

`rj.bc(ArrayList, int, int, int, int, byte)`

The list contains requested track/archive IDs; the next four integers are music
timings. The trailing byte is an obfuscation guard, not a music timing. The
method checks empty/-1/duplicate requests and volume, constructs `nb` requests
from the music archive, and normally calls:

`ij.af(ArrayList, int, int, int, int, boolean, int)`

Here the list contains request objects. The last integer is another guard. The
boolean selects the special immediate-clearing/jingle route in the inspected
callers. When `client.ka` is set (jingle-active branch), `rj.bc` instead calls
`kr.ay(...)` to remember the requests and timings for later handling.

`ij.af` clears/rebuilds pending/task queues, handles already-marked old requests,
remembers normal requests, and constructs loading/transition tasks. It calls
`if.aj(int, int, int, int, byte)` to store the four timings:

| Argument after the request list | Field | Verified consumption |
| --- | --- | --- |
| 1 | `np.ao` | Delay before outgoing fade-out |
| 2 | `np.al` | Outgoing fade-out duration |
| 3 | `np.aj` | Delay before incoming start/fade-in chain |
| 4 | `np.ay` | Incoming fade-in duration |

Actual task-name strings corroborate the roles: `wo` = `FadeOutTask`, `wp` =
`FadeInTask`, `wu` = `DelayFadeTask`, `wk` = `StartSongTask`, and `ws` =
`LoadSongTask`. `wn` transfers requests to the active list, `wq` runs child tasks
concurrently, and `wl` clears old requests.

With existing active requests, the essential task graph is:

```text
add requests -> load track/patches -> concurrent branches:
  incoming: delay(arg3) -> start at volume 0 -> fade in(arg4)
  outgoing: delay(arg1) -> fade out(arg2) -> clear old requests
```

This is not inherently sequential fade-out / silence / fade-in. The two delays
are independent. A silence gap needs suitable relative timing, accounting for
loading and task-completion overhead. No parameters were changed here.

`wu.az(byte)` increments a counter per task service, not by wall-clock time.
Fade tasks adjust float volume by target-volume/duration per service, clamp it,
and write stream volume; zero duration takes the full volume step. `mc.al(int)`
services the task list from the client cycle (`client.in(byte)`, call at offset
83). Parameters are scheduler-update counts, NOT server ticks or milliseconds.
Exact elapsed timing needs measurement; do not convert the observed 1.2 seconds
into an assumed engine parameter.

Other verified branches:

- `bk.ab(int, int, byte)` stops via an outgoing delay/fade/clear chain;
  zero/zero performs immediate cleanup. `rj.bc` with first ID -1 can use that.
- `bl.bp(int, int, int)` creates a jingle request, calls `ij.af` with four zero
  timings and boolean true, then sets `client.ka`.
- `im.as(int, int, int, int, int)` constructs a separate existing-stream swap
  route with `SwapSongTask` and fade/delay tasks. Its existence does not prove
  natural progression uses it.

### Confirmed compiled-client findings: request origins

Direct incoming-packet paths exist independently of scripts:

- In `client.ia(dj, int)`, the `jj.dx` branch reads one track ID and four timings
  from the packet buffer, converts track sentinel 65535 to -1, and calls
  `rj.bc` at bytecode offset **9400**.
- The `jj.bd` branch reads two IDs and four timings, performs the same sentinel
  handling, and calls `rj.bc` at offset **11155**.
- The static counterpart `client.mh(client, dj)` has the respective calls at
  offsets **8822** and **10483**. These identifiers/offsets are version-specific;
  these notes do not assign unverified protocol names to the packets.

Scripts can also request music. The current `ee.bt(int, bl, boolean, byte)`
opcode handler routes **3201** (official `SOUND_SONG`) to `rj.bc`, using one ID
plus four stack integers. Opcode **3221** uses two IDs plus four timings; 3220
calls the stop route; 3222 calls the swap route. Music requests CAN originate
in scripts; this does not establish that the Draynor request did. See the
[official opcode source](https://github.com/runelite/runelite/blob/a5c2494fc913d5b6023edc7883c4933c948f852e/cache/src/main/java/net/runelite/cache/script/Opcodes.java)
for the 3201 name; the other roles were traced in the binary.

Static conclusion: those packet and script paths converge on a shared,
timing-capable request entry point. Missing runtime evidence: which path handled
each experimental transition, and the four timing values actually supplied.

### Does MUSIC_CURRENT_TRACK have special engine behavior?

No dedicated handling of varp number **3883** was found in the inspected native
music path. `client.getVarpValue(int)` reads the generic `lb.af` array. Generic
varp packet handlers write that array, call `client.op(int)` to emit RuneLite
`VarbitChanged` events, then call `xq.gz(int, int)` for option processing.
`xq.gz` dispatches on the cached varp definition's option type; its music-volume
case is not a track-selection case. A whole-jar `javap` search found no direct
3883 or 3885 integer-load references. The request methods above receive IDs
and timings directly instead of reading those numbered varps.

This is evidence AGAINST assuming that writing `MUSIC_CURRENT_TRACK` requests
audio, not proof that the variable is irrelevant to all game logic. Cache
definitions and scripts can refer to IDs dynamically; the varp definition and
all referencing scripts were not exhaustively decoded. The present hypothesis
is observable game/UI state accompanying a separate audio request, pending
runtime correlation. Do not write the varp to test this.

### Natural completion: established behavior and remaining uncertainty

The `nu.aj(nu, int)` MIDI-service routine handles an end-of-track reader event.
When all reader tracks finish and looping is disabled (or timing is zero), it
resets stream state and clears the MIDI reader; otherwise it can loop. This is
an audio-end path, not a varp-3883 special case.

An internal request-cleanup listener path also exists:
`wl`/`gk` -> `ac.az(int, int, int)` -> `nq.il(...)`. The client implements the
listener: `client.il(int, int, int)` can send an outgoing packet (`js.bs`) with
the archive ID, subject to connection/volume/jingle guards. Cleanup also occurs
for stopping/replacement, so this is NOT established as an exclusively natural
completion event. The full chain from spontaneous MIDI exhaustion to server
playlist selection and the observed varp updates has not been established.

Static inspection does not prove either of these claims:

- area replacement and natural progression invoke the same method with different
  timing values in the user's tests;
- the observed ~1.2 seconds is an engine fade/delay rather than playlist, server,
  script, or loading timing.

The shared request machinery makes the first plausible, but runtime arguments
are needed. Also, `CURRENT_TRACK = -1` may be reported after audio has already
ended; it is not automatically advance notice with which to fade the old song.

### Revised hypotheses and next read-only experiment

1. A direct server music request is a stronger candidate for the area transition
   than the nearby script. It fits the ordering, but is not runtime-confirmed.
2. Existing tasks already provide fundamental fade/delay machinery. `rj.bc`
   entry, before `ij.af` changes request/task state, is a concrete candidate
   observation point earlier than audio replacement. Ordering relative to the
   separate varp packet is not guaranteed.
3. If further diagnostic changes are requested, first record bounded public-API
   snapshots of active `(archiveId, isJingle)` pairs alongside the varps. This
   may expose loading/start ordering, but not timing arguments or a guaranteed
   pre-request callback.
4. To settle the engine question, a separately authorized read-only core/debugger
   experiment should record request IDs, four timing arguments, jingle/normal
   branch, monotonic timestamp, and caller category at `rj.bc`/`ij.af`. Compare
   both Draynor directions and multiple natural transitions. Do not dump arbitrary
   packets, unrelated script stacks/arguments, or session data. No such hook was
   implemented in this pass.
5. Design playback modifications only after that correlation. A standard external
   plugin has no verified public timing-control hook for this task; an injected
   or core addition is a separate decision. There is still no justification for
   a hard-coded geographic music map.

Documentation-only verification: review the diff and run `git diff --check`.
No build is necessary for this pass: no plugin source or build files changed.

## Experiment 3: observation-only native request probe (2026-09-22)

### Purpose and authorization

Measure the actual IDs/timing arguments used for both directions of the Draynor
Manor gate crossing and natural progression. The user explicitly authorized a
development-only Java instrumentation agent for this experiment, not playback
control or a live test performed by the agent.

### Implemented instrumentation

- Separate `coreProbe` Gradle source set with `CoreProbeAgent`, `ProbeTransformer`,
  and `CoreProbeLog`. A self-contained ASM 9.10.1 agent jar is built at
  `build/libs/music-transition-core-probe-agent.jar` and automatically attached
  by the existing `run` task via `-javaagent`. Agent sources/dependencies are not
  included in the ordinary plugin or example-style shadow artifact.
- `premain` verifies the full 1.12.39 injected-client SHA-256 recorded above,
  unique target class resources, all four exact static signatures, and absence
  of already-loaded targets. The load-time transformer checks original bytes,
  jar provenance, and application classloader again. Unsupported/missing/changed
  definitions produce an obvious `DISABLED` status and leave the client free to
  launch. No on-disk dependency, Gradle-cache jar, or official jar is patched.
- Exactly these method entries are instrumented:
  - `rj.bc(ArrayList, int, int, int, int, byte)` / `(Ljava/util/ArrayList;IIIIB)V`
  - `ij.af(ArrayList, int, int, int, int, boolean, int)` / `(Ljava/util/ArrayList;IIIIZI)V`
  - `bk.ab(int, int, byte)` / `(IIB)V`
  - `im.as(int, int, int, int, int)` / `(IIIII)V`
- `rj.bc` records a copied ID list and `outgoingDelay`, `outgoingFade`,
  `incomingDelay`, `incomingFade`. `ij.af` records request count, those timings,
  and `specialRoute`. It deliberately does not reflect into internal requests.
  `bk.ab` names only outgoing delay/fade and logs the remaining raw `arg3`;
  `im.as` logs raw `arg1` through `arg5` without inventing semantic names.
- Each invocation emits one `[Music Transition Core Probe]` line with UTC
  milliseconds, `nanoTime`, and the immediate caller. Only `client.ia`/`client.mh`
  classify as `PACKET`, and only `ee.bt` as `SCRIPT`; other callers are `OTHER`.
  Thus an `ij.af` caller of `rj.bc` is `OTHER`, not an inferred upstream category.
  No full stacks, unrelated script arguments, or packet payloads are dumped.
- Core lines go to stdout and `build/music-core-probe.log`, freshly truncated by
  premain for each dev-client run and flushed per line. `build/` is already
  ignored. Status lines distinguish preflight/transform success from invocation
  records. Run one dev client per checkout; copy its log before restarting.
- The plugin keeps all music varp/varclient/widget/volume observations and adds
  ordered, immutable copies of public `(archiveId, isJingle)` values on ClientTick.
  Neither the live list nor request objects are retained. Baseline includes the
  list; later `ACTIVE_MIDI_REQUESTS` lines appear only when values/order change.
  Plugin lines now carry `monoNanos` from the same JVM clock as the core probe.
- Removed the Experiment 2 runtime script subscriptions/buffer/context logging.
  Its findings are preserved above; its implementation remains in Git history.

### Observation versus behavior changes

The transformer adds an entry callback and an exception boundary around that
callback. It loads copies of arguments onto the operand stack, never writes
argument locals, never replaces return values, and always proceeds to the
original body. Original-body exceptions are outside the diagnostic boundary.
There are no music-state writes, skipped methods, or intentional sleeps/waits.
Bytecode IS modified in memory; this is not an assertion of zero overhead:
clock/stack inspection, formatting, stdout and prompt file flushing cost time.
Those measurement effects must be considered when comparing timings.

The probe is intentionally version-specific and load-time-only. Future client
releases, alternate obfuscated copies, custom classloaders, and competing agents
are not assumed compatible. `latest.release` remains in the development build;
a new client needs fresh investigation before updating the hash/signatures.
No inference is made that an active request is already audible or that archive
IDs equal varp track IDs.

### Automated verification

- `.\gradlew.bat build` passed with Java 11 target bytecode (build JVM: JDK 21).
- Synthetic targets test one line per entry, preservation of all argument
  values/list identity and original exceptions, logging-failure isolation,
  signature/provenance rejection, caller extraction/classification, and fresh logs.
- Plugin tests check copied immutable MIDI snapshots, ordering/jingle changes,
  a single baseline, change-only output, and monotonic metadata.
- A forked `-javaagent` / `-Xverify:all` smoke test loaded and resolved all four
  real transformed classes from the verified jar without initialization or music
  method invocation. All four reported `TRANSFORMED`.
- Missing-client and forged-version-jar tests confirm disabled premain still
  allows application main to run. Test logs are separate under
  `build/test-results/`, not the live experiment log.

### Runtime results

The user's live Experiment 3 run produced the following observations:

- Entering Draynor Manor and returning outside each reached `ij.af` with the
  accepted timing tuple `0,60,60,0` and `specialRoute=false`. Both requests
  originated through the packet / `client.ia` path.
- The 60-step outgoing transition corresponded observationally to roughly
  1.2–1.3 seconds before the old active MIDI request disappeared. This is an
  empirical correlation; these integer steps are not being relabeled as
  milliseconds.
- Natural progression first requested archive `[147]` with `0,20,0,0` while
  `MUSIC_CURRENT_TRACK` became `-1`. About 1.18 seconds later, request `[151]`
  used `0,0,0,0` while `MUSIC_CURRENT_TRACK` became `3072`.
- Archive 147's content and role have not been established. In particular, do
  **not** label it as silence from this evidence.
- Both the area replacement and the next-natural-song request supplied
  `incomingFade=0`.

These data confirm that the tested area and natural paths converge on the same
timing-capable request machinery with different timing tuples. They do not prove
that `0,60,60,0` is a universal area signature, nor that archive IDs map directly
to the track varp.

## Experiment 4: opt-in area incoming-fade override (2026-09-22)

### Question

Does the already-existing incoming-fade parameter produce a smooth fade-in if
the exact, repeatedly observed Draynor area tuple is changed from `0,60,60,0`
to `0,60,60,60` at `ij.af` entry?

### Deliberately narrow intervention

- Ordinary `.\gradlew.bat run` still builds the observation-only transformer;
  it does not emit bytecode that writes an argument local.
- `.\gradlew.bat runAreaFadeTest` passes the explicit agent mode
  `area-incoming-fade-test`. Only that transformer variant can replace an
  argument, and only when `specialRoute == false` and all four original timings
  are exactly `0,60,60,0`.
- The first three timings are preserved and only `incomingFade` changes from 0
  to 60. Natural tuples `0,20,0,0` and `0,0,0,0`, special/jingle routes, and all
  other timing combinations pass through unchanged.
- Every accepted override writes one audit line containing both
  `originalTimings=[0,60,60,0]` and `effectiveTimings=[0,60,60,60]`. If the
  audit write fails, the original incoming-fade value is returned.
- The Experiment 3 full-jar hash, unique-resource, exact-signature, classloader,
  provenance, original-byte, and not-already-loaded checks remain in force.
  Unknown agent modes disable the probe rather than guessing.

This is a controlled signature-matching experiment, not a proposed general
area detector. It adds no silence gap, does not change outgoing fade, and does
not write volume, varps, scripts, track selection, or any other client state.

### Automated verification

- `.\gradlew.bat build` passed with 12 core-probe unit tests (zero failures),
  the plugin tests, fail-closed startup checks, and both agent-mode smoke tests.
- Synthetic execution proves observation mode preserves `0,60,60,0`; opt-in
  mode changes only its incoming fade; both natural tuples, a special route,
  and one-at-a-time unrelated tuple variations pass through unchanged. The
  fixture's original body executes exactly once after an accepted override.
- Separate `-Xverify:all` processes loaded all four transformed classes from the
  verified injected-client jar in `OBSERVE_ONLY` and
  `AREA_INCOMING_FADE_TEST` modes. No game method was invoked.

### Runtime results

The user's live Experiment 4 test intercepted multiple real area transitions.
Each accepted `ij.af` call had original timings `0,60,60,0`, and the opt-in
override changed only incoming fade, yielding `0,60,60,60`. This was observed
in both directions at the Draynor Manor boundary and at additional tested
music boundaries involving archive IDs 127, 151, and 107.

The user reported that a fade-in was probably audible but subtle. Track
composition materially affects perception: naturally quiet introductions can
hide the effect, while immediate, strong introductions make abrupt starts more
apparent. This is a subjective report, not a measured fade curve.

Archive 151 was requested during natural progression with timings `0,0,0,0`
in Experiment 3, but during an area-triggered transition it appeared with
`0,60,60,0` here. The timing tuple therefore depends on request context; it
is not simply a fixed property of the requested archive. The tested tuple is
still an experimental match, not a general area-detection rule.

Whether login or startup music uses a fade remains a future question. It was
not investigated in this experiment.

## Experiment 5: configurable area incoming-fade tuning (2026-09-22)

### Motivation and hypothesis

The 60-step incoming fade was subjectively subtle in Experiment 4. This
development-only harness tests 120 steps by default as a stronger native fade,
with a bounded command-line value for comparisons (for example 60 or 90).
The previous 60-step outgoing transition corresponded observationally to
roughly 1.2–1.3 seconds before the old active MIDI request disappeared. Native
music timings are scheduler steps, not milliseconds; that observation is not a
general conversion factor or a prediction of audible incoming-fade length.

### Implementation and limits

- `.\gradlew.bat run` attaches the observation-only agent, with no argument
  local write and no reading of `areaIncomingFade`.
- `.\gradlew.bat runAreaFadeTest` uses 120 steps by default.
  `-PareaIncomingFade=<value>` selects a value from 1 through 300 inclusive.
  Gradle rejects malformed, negative, zero, and larger values before launching
  the client. The Java agent independently validates its mode and value before
  arming; unexpected arguments disable the probe.
- The opt-in bytecode still matches only `specialRoute=false` and the original
  tuple `0,60,60,0`. It preserves outgoing delay 0, outgoing fade 60, and
  incoming delay 60, replacing only incoming fade. An `ARMED` line reports
  `configuredIncomingFade=<value>`. Every accepted override records
  `originalTimings=[0,60,60,0]` and
  `effectiveTimings=[0,60,60,<value>]`.
- The exact 1.12.39 injected-client hash, signatures, resource uniqueness,
  loader, provenance, and original-byte checks remain unchanged. The harness
  does not alter natural tuples, jingles, volume, track selection, or other
  client state. No startup/login-specific handling was added. Whether a startup
  request could ever present the same eligible tuple remains unknown; this
  experiment makes no claim about that path. The timing signature remains an
  experimental match, not a general area-detection design.

### Automated verification

- `.\gradlew.bat build` passed. The verified 1.12.39 client classes loaded
  under both observation and 120-step test agents with `-Xverify:all`; no game
  music method was invoked by smoke tests.
- Synthetic `ij.af` tests passed for configured 60, 90, and 120 steps. They
  verified the unchanged first three arguments, original method execution,
  `ARMED` value, and original/effective audit fields. Natural, special-route,
  and unrelated timing calls kept their original incoming fade.
- Agent parsing rejected malformed, negative, zero, and over-300 values.
  A real Gradle invocation with `-PareaIncomingFade=abc` failed at
  `runAreaFadeTest` before JavaExec launched the development client.

### Runtime results

The user's live Experiment 5 test armed with `configuredIncomingFade=120`.
Multiple real area transitions entered `ij.af` with original timings
`0,60,60,0`, and the harness recorded effective timings `0,60,60,120` for
each accepted override.

The user reported that the overall transition felt more gradual than vanilla
and that the existing outgoing fade sounded good. The incoming audio did not
sound like a continuous smooth ramp: subjectively there was silence, then a
subtle partial-volume entrance, then something close to an abrupt jump to full
volume. This is a listening observation, **not** proof that the engine writes
an abrupt volume jump. Experiment 6 must measure the native stream-volume
writes and their ordering before drawing that conclusion.

Login/startup fading remains a future question and was not investigated.

## Experiment 6: native incoming stream-volume ramp observation (2026-09-22)

### Question

Measure the setter calls behind the subjective Experiment 5 report. Does the
incoming stream start at zero, receive many incremental writes, or receive an
early target-volume write from another task? A smooth requested-volume series
would make track composition or audibility a stronger explanation for the
perceived jump, but the setter calls alone do not measure PCM output or human
loudness.

### Re-verified binary evidence before hooking

- The local `injected-client-1.12.39.jar` again hashes to SHA-256
  `25f42961c400bd9dfff1554402441c0ba6d1cffd011163cb9b0b4c42ae194f85`.
- `javap -p -s -c` confirms `nu.az(int, int)` is a **public instance method**
  with exact JVM descriptor `(II)V`. Its ordinary body stores the first integer
  into the stream's encoded `aj` volume field while holding its existing lock;
  the second integer is not the volume value. The hook records the first
  argument at method entry and never reads or changes `aj`.
- Verified `invokevirtual nu.az:(II)V` call sites exist in `wp` (`FadeInTask`),
  `wo` (`FadeOutTask`), and `wk` (`StartSongTask`). A hook at the shared setter
  can therefore identify those writers and reveal another caller if present.

### Implemented observation

- The existing `runAreaFadeTest` eligibility and configurable incoming-fade
  replacement are unchanged. A successfully audited eligible override starts
  a 4.5-second volume-observation window. The window resets on another accepted
  override and has a hard limit of 512 setter lines plus one limit marker.
- During the window, `nu.az` entry logs UTC milliseconds, `nanoTime`, elapsed
  nanoseconds, window number, requested stream volume, `System.identityHashCode`
  of the stream, immediate caller, and the verified task category
  (`FadeInTask`, `FadeOutTask`, `StartSongTask`, or `OTHER`). Stream identity is
  stable for one object in a JVM run, but is not an archive ID and could in
  principle collide. The request objects, script inputs, PCM samples, and
  obfuscated stream fields are not dumped.
- Ordinary `run` still has no timing substitution. It verifies the fifth
  target's provenance/signature but returns the original `nu` class bytes;
  therefore it produces no stream-volume lines. The opt-in agent transforms
  `nu.az` without changing its arguments, return, exception path, or setter
  body. The existing full-jar hash and per-class byte/provenance checks also
  cover this fifth target.
- Interpret `requestedVolume` as a setter input. The inspected ordinary body
  writes that input to its volume field, but an entry log is not proof that a
  call completed, nor that the audible signal had that amplitude. Group lines
  by `volumeWindow` and `streamIdentity`, then compare `task` and time ordering.
  Synchronous logging itself can add timing overhead.

### Automated verification

`.\gradlew.bat build` passed. Synthetic fixtures verify window
eligibility/expiry, stable stream identity, unmodified setter arguments and
body execution, conservative caller categories, and the 512-line limit.
Separate `-Xverify:all` smoke processes load all five target classes from the
verified client jar: `nu.az` is transformed only in opt-in mode and reported
`VERIFIED_UNCHANGED` in normal mode. No game music method was invoked.

### Runtime results

The user reports that the Experiment 6 native setter trace showed the incoming
fade working correctly: requested stream volume rose gradually through
intermediate values. The perceived abrupt entrance is therefore not explained
by an abrupt native setter jump in this test. The user identified the current
effective tuple `[0,60,60,120]` as making the transition feel sequential:
the new track waits 60 scheduler steps before its start/fade-in branch.
No exact write count or raw trace was supplied here, so these notes do not
invent one or infer PCM loudness from setter values.

The intended behavior is now an overlapping crossfade: start the outgoing
fade immediately, start the incoming track at volume zero without the prior
60-step delay, and let both native fades overlap. Preserve the subjectively
good 60-step outgoing fade. Login/startup fading remains a separate future
question.

## Experiment 7: overlapping native area crossfade (2026-09-22)

### Motivation and intended test

Experiment 6 showed that the native incoming setter ramps through intermediate
volumes. The subjectively sequential Experiment 5 transition used effective
timings `[0,60,60,120]`: the 60-step incoming delay deferred the new track's
start/fade task. The intended product behavior is a crossfade: old music begins
fading immediately, new music starts at zero without that delay, and the fades
overlap. The existing outgoing 60-step fade sounded good and is retained.
This is the rationale for a test, not a claim that live audio overlap has already
been demonstrated. Stream loading or other task scheduling could still affect
the audible timing.

### Implemented intervention and safeguards

- Normal `run` remains observation-only. Only `runAreaFadeTest` can change
  arguments at the verified `ij.af` entry, and only when `specialRoute=false`
  and the *original* complete tuple is exactly `[0,60,60,0]`.
- Outgoing delay and fade remain `0,60`. The command-line
  `-PareaIncomingDelay=<integer>` accepts 0–60 scheduler steps and defaults to
  0. `-PareaIncomingFade=<integer>` retains its 1–300 range and 120 default.
  The default effective tuple is `[0,60,0,120]`; for example delay 30 and fade
  90 give `[0,60,30,90]`. These units are native steps, not milliseconds.
- Each accepted override logs both `originalTimings=[0,60,60,0]` and the
  effective tuple. The `ARMED` line reports both configured values. Malformed or
  out-of-range project properties fail before the dev client starts; the agent
  also independently validates its arguments and fails closed.
- The bounded, synchronous `nu.az(II)V` volume trace is disabled by default,
  even in `runAreaFadeTest`. Explicit `-PprobeStreamVolume=true` enables it for
  a separate measurement run. Without that flag, the verified `nu` bytes pass
  through unchanged. This avoids high-volume logging overhead during subjective
  listening comparisons.
- The exact 1.12.39 injected-client hash, method signatures, loader,
  provenance, resource-uniqueness, original-byte, and not-already-loaded checks
  remain in force. Natural signatures, special/jingle routes, and unrelated
  timing combinations pass through unchanged. There is no change to fade
  curves, global volume, track selection, or login/startup behavior.

This exact timing match remains an experimental filter, not a final general
area-detection architecture. Whether login/startup fading needs treatment is
still a future question, not investigated here.

### Automated verification and status

`.\gradlew.bat build` passed, including synthetic method-execution tests,
normal observation-mode pass-through, default `[0,60,0,120]`, custom delay/fade
values, invalid argument parsing, natural/special/unrelated pass-through,
default-disabled setter tracing, and `-Xverify:all` agent smoke processes with
the setter probe both off and on. The tests do not invoke real game music
methods or establish subjective audibility. No live RuneScape test was
performed for Experiment 7.

### Experiment 7 follow-up: four-value tuning harness (2026-09-23)

The user requested full native-tuple tuning for subjective crossfade comparisons.
This extends the opt-in experiment; it is not new evidence that the original
`[0,60,60,0]` timing signature is a universal area detector. Eligibility is
still **only** `specialRoute=false` plus that exact *original* four-value
tuple. Natural requests, jingles/special routes, and other tuples remain outside
the override even when their values resemble a configured effective tuple.

`runAreaFadeTest` now accepts `-PareaOutgoingDelay`, `-PareaOutgoingFade`,
`-PareaIncomingDelay`, and `-PareaIncomingFade`, each an integer from 0 through
300 native scheduler steps. Defaults remain `[0,60,0,120]`. For example,
`-PareaOutgoingDelay=0 -PareaOutgoingFade=90 -PareaIncomingDelay=0
-PareaIncomingFade=150` requests `[0,90,0,150]` for an eligible original
`[0,60,60,0]`. Zero is deliberately allowed for every value, including fades;
the verified fade tasks treat zero duration as a full-volume step. These are
native scheduler steps, not milliseconds.

One injected `ij.af` entry callback decides eligibility and returns an entire
four-integer tuple. It returns the unmodified original tuple for any nonmatch
or audit failure. Only the opt-in transformer writes the four argument locals.
The `ARMED` line reports all four configured values and the override audit
records both `originalTimings` and `effectiveTimings`. The 1.12.39 hash,
signature, classloader, provenance, original-byte, and load-time safeguards are
unchanged. `probeStreamVolume=false` remains the default; explicit
`-PprobeStreamVolume=true` retains the bounded setter trace. Ordinary `run`
remains observation-only. No track selection, global volume, fade-curve,
natural-transition, jingle, or login/startup behavior was changed.

Synthetic tests cover default/custom/nonzero-delay/zero/max tuples, invalid
values in each field, exact-match and nonmatch behavior, normal observation
mode, disabled-by-default stream tracing, audit-I/O failure returning all four
originals, and original-body execution. Gradle was also checked to reject
malformed, negative, and over-300 project properties before JavaExec launch. The
verified-client smoke tests load the transformed methods with `-Xverify:all`
without invoking game music methods. A live RuneScape test was not performed
in this implementation pass.

## Architecture pass: preferred handoff, login path, and stable RuneLite integration (2026-09-23)

Research/documentation only: no live test, code change, playback change,
upstream submission, or final UI in this pass. Earlier Experiment 7 notes
describe an earlier overlapping-crossfade *hypothesis*, not the user's later
subjective preference.

### Preferred area timings and future UI

The user completed subjective area tuning and reports that effective native
`[outgoingDelay,outgoingFade,incomingDelay,incomingFade] = [0,200,200,200]`
produces the gradual transition they wanted. This is candidate **Smooth
Defaults**. The observed original area tuple `[0,60,60,0]` is **Vanilla
Timings**. These are scheduler steps, not milliseconds. In Smooth Defaults,
the old track starts fading immediately for 200 steps; incoming start waits
200 steps and then fades in for 200. This is a gradual *handoff* scheduled
near outgoing fade completion, **not** an overlapping crossfade. Loading and
task service can shift exact audible timing. The current opt-in Gradle harness
retains its historical `[0,60,0,120]` default; it was not changed here.

The eventual plugin should have a master switch, four separate area controls,
`Restore Smooth Defaults` -> `[0,200,200,200]`, and `Restore Vanilla Timings`
-> `[0,60,60,0]`. Disabled means **do not mutate native timing arguments**,
not “substitute vanilla values.” A separate optional `Fade music in on login`
setting should be independently configurable, with 200 steps as a candidate
default. None of this UI or login behavior was implemented.

### Confirmed pinned-binary and existing-log findings

- Rechecked the [official launcher bootstrap](https://static.runelite.net/bootstrap.json)
  on 2026-09-23. It still lists `injected-client-1.12.39.jar`, SHA-256
  `25f42961c400bd9dfff1554402441c0ba6d1cffd011163cb9b0b4c42ae194f85`.
  All short native names below are verified only for that jar. The exact
  `ij.af` descriptor is `(Ljava/util/ArrayList;IIIIZI)V`; the trailing int
  is an obfuscation guard, not a fifth timing.
- In the **existing** `build/music-core-probe.log` from an earlier run, the
  first startup-correlated request at 2026-09-24 00:00:20.675 UTC was
  `rj.bc requestedIds=[49]`, original `[0,0,0,0]`, caller `client.ia`
  (packet path). At 00:00:20.685, `ij.af` **accepted** one request with
  `[0,0,0,0]`, `specialRoute=false`, caller `rj.bc`. No override applied.
  A second `rj.bc [49] [0,60,60,0]` at 00:00:20.697 has **no** matching
  accepted `ij.af` call in this trace; do not mistake that request attempt
  for a scheduled transition. Later accepted area requests did use
  `[0,60,60,0]` and were overridden to `[0,200,200,200]`.
- Focused diagnostic lines in the prior RuneLite `client.log` show an empty
  active MIDI list and unavailable player location at 00:00:19.238, player
  location available by the subsequent music-varp update, and active MIDI
  changing `[] -> [{archiveId=49,isJingle=false}]` at 00:00:20.718. This
  strongly associates the accepted `[49] [0,0,0,0]` with first normal
  music after login. However no GameStateChanged marker or explicit login
  reason was logged: this is **startup-correlated**, not proof that all or
  only login requests use this tuple. The varp track value is not assumed
  to equal the archive ID. Only diagnostic lines were inspected; no account
  or session material was read or reproduced.
- Verified 1.12.39 request flow for this call is `client.ia` (packet) ->
  `rj.bc` -> `ij.af`. `rj.bc` can suppress duplicates/sentinels or divert
  requests, so only `ij.af` demonstrates task scheduling. The exact packet
  variant for archive 49 was not separately logged.
- At `ij.af`, `if.aj` stores the four timings before task construction. The
  pinned no-active-request branch builds loading, incoming delay, start, and
  fade-in tasks, with no old-request fade-out branch. Verified
  `StartSongTask` sets the individual MIDI stream to zero before starting;
  `FadeInTask` ramps that request's stream toward its target over the
  incoming-fade count. Thus a first normal request that reaches this branch
  could *technically* use `incomingFade=200` without changing global volume.
  This is a static capability, **not** a live login-fade result or proof of
  how muted/jingle/duplicate/relogin states behave.

### Current public API and upstream issue, rechecked

- Current [Client.java](https://github.com/runelite/runelite/blob/master/runelite-api/src/main/java/net/runelite/api/Client.java)
  exposes `getActiveMidiRequests()` and global `getMusicVolume()` /
  `setMusicVolume()`, but no accepted-request timing hook/setter. Current
  [MidiRequest.java](https://github.com/runelite/runelite/blob/master/runelite-api/src/main/java/net/runelite/api/MidiRequest.java)
  exposes `isJingle()` and `getArchiveId()`, not timing or request reason.
  This is about inspected **public interfaces**, not absence of internal
  machinery. [Issue #10692](https://github.com/runelite/runelite/issues/10692)
  remains open as checked 2026-09-23; it broadly asks for music-player APIs
  and does not specify or approve this hook.
- Current [Hooks.java](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/callback/Hooks.java)
  routes ordinary `post` to the event bus synchronously, unlike
  `postDeferred`. The current [EventBus.java](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/eventbus/EventBus.java)
  invokes subscribers immediately in priority/name order and catches their
  `Exception`s. Thus a synchronous mutable pre event is technically possible;
  a deferred event would be too late. Exception handling alone does not
  provide atomic validation/conflict policy for multiple timing writers.
- The public source inspected here does not contain the private injected
  client mapping for `ij.af`. Its exact mixin/injection source file, mapping
  strategy, and maintainer acceptance remain unverified. Do not invent a
  private RuneLite class name or expose obfuscated names in public API.

### Three possible integration designs (proposals, not implementations)

1. **Synchronous mutable pre-schedule event:** fire once after a native
   request has been accepted but before `if.aj`/task construction. Include
   immutable original four-tuple, editable effective four-tuple, request IDs
   if safely available, and verified special/jingle context. Advantage:
   smallest familiar `@Subscribe` plugin surface; disabled plugins leave it
   untouched. Risks: multiple subscribers can conflict, priority/order is
   observable, invalid values need rejection, and callbacks must be fast.
   A name such as `MusicTransitionPreScheduled` should communicate mutation;
   do not call a writable object a merely observational event.
2. **Client API policy callback/provider:** give the plugin immutable
   original timings/context and accept one complete replacement or explicit
   `unchanged`. Advantage: atomic one-writer, fail-closed semantics and clear
   validation. Risks: registration/lifecycle and cross-plugin ownership need
   rules; it still needs an internal injection point. A late setter on an
   already built request or global music volume would not solve this.
3. **Core-only injected policy with plugin configuration bridge:** core owns
   timing validation/conflicts. Advantage: central control; risks: more
   upstream policy code, more coupling to this use case, less reusable API.
   The 1.12.39 Java-agent/obfuscated-name harness is development-only and
   must not become a Plugin Hub implementation.

**Recommended smallest upstream change:** a narrow synchronous intervention
at the accepted normal-music scheduler entry, before timing storage/tasks,
with an immutable original tuple, a single validated effective tuple, and
`unchanged`/fail-closed behavior. A pre event best matches the existing bus
if RuneLite maintainers accept explicit writer-order/conflict semantics;
otherwise prefer the callback/provider for atomicity. Rewrite all four
timing arguments as one decision, use native-step units, validate a bounded
nonnegative range, and leave special/jingle routes alone unless separately
specified. The event must not assert `AREA` when the client did not establish
that semantic reason.

Likely upstream modules/files: a new public event/value type under
`runelite-api/src/main/java/net/runelite/api/events/` (or a small type plus
callback contract under `net.runelite.api`); `Client.java` only for the
provider design; the injected-client mapping/hook layer at accepted scheduler
entry; and injection/core tests for normal, special, no-active, invalid, and
unchanged cases. Existing `runelite-client` callback/EventBus code may need
no change for an ordinary synchronous event; a provider would require
registration/bridge work. These are **likely touchpoints**, not a verified
upstream patch or known private file paths. The eventual third-party plugin
would own user settings, preset actions, enable policy, request-context
policy, audit/UX and tests; it must not own raw bytecode or geographic maps.

### Can one hook cover area, natural progression, and login?

The observed area calls, natural-progression calls, and startup-correlated
request all reached `ij.af`. So **one hook can expose generic accepted-request
timings** for those observed cases. It cannot safely label semantic `AREA`,
`NATURAL`, or `LOGIN` from the tuple alone. A later natural request and the
startup-correlated one both used `[0,0,0,0]`; archive 151 used different
tuples in area and natural contexts. `specialRoute=false` is not proof of
geographic change, and an empty active list might also follow track end,
stop, or music re-enable. A polished independent area/login/natural policy
probably needs additional verified source context or a second higher-level
hook. The literal `[0,60,60,0]` remains an **experimental** area eligibility
signature, not a final architecture.

Smallest next **read-only** experiment: for first accepted normal requests
on login/relogin, then natural end and music re-enable, correlate bounded
GameStateChanged markers with accepted IDs/timings, native active-list-empty
state, and jingle/special state. Do not capture packet payloads, account or
session data. This would test whether `[0,0,0,0]` repeats for login and
whether any safe startup predicate exists; it would not authorize changing
login playback, building the UI, or opening an upstream PR.

## Natural-progression MVP and native end-path trace (2026-09-23)

### New live result and priority

The user reports: **at least one live teleport into a different music area
followed the currently overridden replacement path and therefore received
the smooth `[0,200,200,200]` transition.** This proves only the tested case,
not all teleports. Natural song ending remained perceptibly vanilla because
the development harness still matches only the original `[0,60,60,0]` area
signature. Smooth natural progression is now **MVP**, not optional polish.
Login/startup fade is a nice-to-have and must not block it. Do not broaden the
area harness match or mutate either natural request on this evidence alone.

### Artifact and signature recheck before native analysis

The current [official bootstrap](https://static.runelite.net/bootstrap.json)
still lists `injected-client-1.12.39.jar`, SHA-256
`25f42961c400bd9dfff1554402441c0ba6d1cffd011163cb9b0b4c42ae194f85`.
Offline Gradle `dependencyInsight --dependency injected-client --configuration
testRuntimeClasspath` resolved `net.runelite:client:latest.release` to client
**1.12.39** and its injected-client dependency to **1.12.39**. The cached
injected jar is 4,207,579 bytes and matches that SHA-256. `javap -p -s`
reconfirmed `nu.aj(Lnu;I)V`, `rj.bc(Ljava/util/ArrayList;IIIIB)V`, and
`ij.af(Ljava/util/ArrayList;IIIIZI)V`. These are verified names/signatures
for this pinned artifact only; a future release requires fresh inspection.
No behavior-changing instrumentation was attempted.

### Confirmed native data flow, with explicit limits

1. In `nu.aj(nu,int)`, the MIDI reader processes a terminal track event
   (`nc.ao(...) == 1`), marks that reader track done, and checks whether all
   reader tracks are complete. If complete and the stream is not looping (or
   the reader time is zero), it resets the stream and clears the MIDI reader
   (`nu.kx`, `nc.af`). Other branches continue MIDI events or loop. This
   specific completion branch does **not** call `rj.bc`, `ij.af`, mutate
   `np.ac`, or select another archive. It is a native audio-end point, but
   static inspection alone does not prove when it ran relative to the user's
   observed first `[147]` request. In particular, `MUSIC_CURRENT_TRACK=-1`
   should not be treated as advance notice of remaining audible MIDI.
2. `rj.bc` receives **upstream-supplied** archive IDs and all four timings.
   It checks empty/sentinel/duplicate and music-volume/jingle conditions,
   constructs normal `nb` requests from the music archive for accepted IDs,
   and normally forwards them to `ij.af(...,specialRoute=false)`. During
   jingle-active handling it can remember the request/timings instead. The
   1.12.39 callers include packet handling (`client.ia` and copies), script
   song opcodes (`ee.bt` and copies), and other direct scheduler/replay paths.
   No literal 147/20 source or natural-reason parameter was established in
   the inspected request function. The earlier `[147] [0,20,0,0]` runtime
   record did not preserve its upstream caller category in the surviving
   core-probe log, so its exact origin remains unproven.
3. On the ordinary accepted path, `ij.af` records normal requests in
   `np.ac` (remembered non-jingle requests), stores the four timings via
   `if.aj`, then constructs loading/start/fade/clear tasks. `np.ac` can be
   replayed after other music states such as a jingle; it is not a
   natural-progression flag. `np.ab` is the active-request list, not proof
   that every listed stream remains audible. With an old active request,
   outgoing and incoming task chains can run concurrently after loading.
   Under `[0,20,0,0]`, the old request is scheduled for a 20-step fade/clear
   while the new request's start has no delay or fade. Under `[0,0,0,0]`,
   outgoing clear and incoming start/fade are immediate native steps.
4. The outgoing `wl` cleanup task removes an old `nb` from `np.ab` and calls
   `ac.az(archiveId,fileId,...)`; listeners include `client.il`, which may
   send a packet containing the removed archive ID if connection, volume,
   and jingle guards pass. That callback occurs for ordinary replacement
   cleanup too, not exclusively natural end. A server response to this
   notification is a plausible source of the later next-song request, but
   the inspected bytecode and existing logs do **not** prove that causal
   chain or identify the packet carrying it. `mc.al` services already-built
   tasks; it does not itself choose the next archive.

### What archive 147 is, and what remains unknown

The prior Experiment 3 accepted `[147] [0,20,0,0]` before a later next-song
request `[151] [0,0,0,0]` about 1.18 seconds later. Archive 147 is **not**
an ignored sentinel at `rj.bc`/`ij.af`: the normal request machinery creates
an `nb` for the supplied ID and routes it through the music-archive loading
task. Active-list membership does not prove successful loading or audible
playback. Existing *plugin* diagnostics from two other windows with the
natural-style varp sequence show that 147 became active with
`isJingle=false`:

| Prior diagnostic window (UTC) | Active request sequence | Timing relative to 147 appearing |
| --- | --- | --- |
| 2026-09-24 00:48:31–32 | `[151] -> [151,147] -> [147] -> [147,49] -> [49]` | Old 151 removed after ~0.462 s; next 49 added after ~1.200 s. |
| 2026-09-24 00:52:35–36 | `[76] -> [76,147] -> [147] -> [147,327] -> [327]` | Old 76 removed after ~0.461 s; next 327 added after ~1.200 s. |

In both windows `MUSIC_CURRENT_TRACK` changed to `-1` and
`MUSIC_LAST_TRACK` updated shortly before 147 appeared; the next current
track was assigned about 1.2 seconds later. These snapshots do **not** include
the native timing arguments of those particular windows, nor whether 147
produced audible PCM, what its MIDI content is, or why it was selected.
Do not label archive 147 silence, a placeholder, or a geographic music track
without further evidence. Its recurring intermediate *position* is observed;
its semantic role is not established.

### Natural versus startup before task construction

The later next-song request `[0,0,0,0]` is not distinguishable from the
startup-correlated `[0,0,0,0]` by tuple alone. Nor does `specialRoute=false`
classify it. At the later request, a recently accepted 147 may still be in
`np.ab`/`np.ac`; at the observed startup request the public active list was
empty. That is a **candidate correlation**, not a reliable generic reason:
an active list may be empty after natural exhaustion/stops, and remembered
requests can exist for replacement and jingle recovery. The varp sequence
`CURRENT_TRACK=-1` plus `LAST_TRACK` update is also useful historical
context, but it is not an explicit native scheduling reason, and a startup
baseline can include `CURRENT_TRACK=-1`. A short-lived marker from the
verified MIDI-end branch might help, but the observed first request could
precede that branch, so its ordering must be measured. No stable condition
available **at `ij.af` alone** has yet been proven to classify every natural
versus startup request before task construction.

### Candidate natural policy and generic API implication

The smallest plausible natural intervention is to leave the first
`[147] [0,20,0,0]` request native and, **only after a reliable two-request
natural correlation is established**, change the *later real next-song*
request from `[0,0,0,0]` to candidate `[0,0,0,200]`. That changes only its
incoming-fade duration. The pinned `StartSongTask` begins its stream at zero;
`FadeInTask` then raises the individual stream volume over 200 native steps.
This avoids deliberately replaying/fading the already-ending old song,
delaying the next song by 200 steps, or changing archive 147's unknown role.
However, if 147 has audible content or the existing ~1.2-second interval is
already a silence gap, a 200-step fade might still feel too quiet/late. A
shorter incoming duration (e.g. 120 steps) is a second **test candidate**,
not a chosen policy. Neither tuple should be applied by literal
`[0,0,0,0]` matching because startup has the same original tuple. No
natural timing override was implemented or live-tested here.

The eventual RuneLite addition should be a **generic accepted-music-schedule
pre-task-graph hook**, not an area-only API. A public contract could expose
immutable original and mutable/returned effective
`outgoingDelay/outgoingFade/incomingDelay/incomingFade`, safely copied request
archive IDs, genuinely known jingle/special context, and (if verifiable)
request origin or preexisting/remembered request summaries. It must not
invent `AREA`, `NATURAL`, `TELEPORT`, or `LOGIN` enum values. One hook at
`ij.af` can perform generic four-value intervention for accepted requests;
the *policy distinction* may require a second, read-only native-end/source
marker or richer upstream context. MVP policy goal: smooth ordinary
replacements (including the tested teleport); smooth reliably identified
natural next-song arrivals; leave startup/login and special/jingles native.
Login fade remains future optional work. The development agent's obfuscated
names and literal area timing match are not the production API.

### Smallest next experiment; no new probe code in this pass

The surviving core log contains the startup and area paths but not the native
caller/accepted-state lines for those two natural windows. Static inspection
cannot decide whether the `[147]` request precedes or follows the actual
`nu.aj` terminal branch, or whether B is selected by the server after the
old-request cleanup notification. A **bounded, observation-only** next run
should add one `NATURAL_END_OBSERVED` monotonic/stream-identity marker at the
verified non-loop terminal `nu.aj` branch, then correlate for ~2 seconds:
`rj.bc` upstream caller category and IDs, accepted `ij.af` IDs/timings,
special flag, copied active/remembered IDs *before* mutation if safely
accessible without reflection, and existing plugin varp/active-list changes.
Only emit the marker at terminal completion and a small bounded number of
subsequent accepted request lines; do not dump packets, scripts, PCM,
credentials, or unrelated state. Compare natural endings against startup,
replacement/teleport, and jingle controls. Record correlation, not a causal
`NATURAL` label, until ordering and specificity are demonstrated. This pass
did not add a new hook or launch the game: the existing static evidence and
logs narrow the one remaining attribution question, and no behavior change
is justified yet.

## Opt-in zero-timing incoming-fade behavior test (2026-09-23)

This experiment **supersedes the previous plan to require natural-versus-startup
classification before a timing test**. The user deliberately wants to test
whether the two *currently observed* ordinary `[0,0,0,0]` cases can share the
same native incoming fade: startup-correlated first music and the later real
next-song request during natural progression. A positive listening result
could simplify the eventual policy; a bad startup or natural result would
motivate finer context. It does **not** establish that every zero tuple has
either semantic meaning, and `ZERO_TIMING_INCOMING_FADE` is the only new
request audit label (not `LOGIN` or `NATURAL`).

Normal `run` remains observation-only. In `runAreaFadeTest` only, the prior
`specialRoute=false` plus exact original `[0,60,60,0]` area match still uses
the four configured area values and a separate `override=AREA_TIMINGS` audit.
The newly authorized second branch requires `specialRoute=false` and **all
four** original arguments exactly `[0,0,0,0]`; it preserves the first three
and sets only incoming fade to `-PnaturalIncomingFade` (integer 0–300,
default 200). Its audit records
`override=ZERO_TIMING_INCOMING_FADE originalTimings=[0,0,0,0]
effectiveTimings=[0,0,0,200]` at the default. The first observed natural
request `[0,20,0,0]`, special/jingle calls, and unrelated tuples remain
untouched. The startup-correlated zero request is intentionally in scope for
the **development test**, despite login fade remaining optional for MVP.

Both matches are decided in the existing single `ij.af` argument callback;
the effective four-value array is handed to the original method only after a
successful audit. Audit failure returns the original four values. The same
1.12.39 hash, exact signatures, classloader, provenance, unique-resource,
original-byte, and not-already-loaded gates remain. `probeStreamVolume=false`
stays the default. No global volume, track selection, fade curve, varp, or
script mutation was added. The existing area Gradle defaults remain
`[0,60,0,120]`, while the user's preferred `[0,200,200,200]` area values
can still be supplied explicitly; these are independent of the new
zero-tuple incoming fade. Scheduler values are not milliseconds.

Synthetic tests cover default/custom/zero/max zero-tuple fade values,
independent area configuration and audit, ordinary observation-only mode,
`[0,20,0,0]`, special and unrelated pass-through, agent parsing failures,
and audit-failure fallback. `.\gradlew.bat build --offline` passed, including
22 core-probe tests (zero failures/errors), plugin tests, and the existing
real-jar `-Xverify:all` observation, opt-in, volume-probe, and fail-closed
smoke processes. The smoke processes load classes but do not call game music
methods. A real Gradle invocation with `-PnaturalIncomingFade=abc` failed
before JavaExec launched the client, as intended. No live RuneScape test was
performed here.

The planned empirical comparison was to test first music after startup and
stationary natural endings with volume tracing off, then decide whether
production needed separate classification. Its live result is recorded below.

### Live results: area and ordinary zero-timing overrides (2026-09-23)

The user completed that opt-in listening test with area/replacement calls
`[0,60,60,0] -> [0,200,200,200]` and ordinary zero-timing calls
`[0,0,0,0] -> [0,0,0,200]`. The tested area/context transitions still had
the desired smooth handoff, and the previously tested teleport continued to
inherit that behavior. On natural song progression, the incoming real song
faded in gradually. The first normal song after login faded in too. Natural
end-of-song cadence felt somewhat different from an area/context transition,
but was acceptable and needs no further tuning for this MVP. Incoming fade
200 native scheduler steps remains the desired default for the zero-timing
case. These are user-reported live results; no independent audio waveform or
new raw trace was analyzed in this documentation pass.

For the two *observed* ordinary zero-timing cases, separate startup-versus-
natural classification is not needed for the MVP. This does not prove that
every possible `[0,0,0,0]` request has the same semantics or will be safe to
change in a final plugin. The local behavior-prototyping phase is essentially
complete. Keep the development harness unchanged as a regression/reference
implementation; add no more diagnostic classifiers absent a concrete need
discovered during upstream integration.

## Official RuneLite source-level bridge feasibility (2026-09-23)

### Checkout and confirmed source findings

- Cloned the current official `https://github.com/runelite/runelite.git` into
  `C:\Projects\runelite`, fetched `origin/master`, and created the local-only
  `music-transition-api-prototype` branch at commit
  `0d4278355dd845629fc61217e6c4e67a551c69b4`.
- The build declares version `1.12.40-SNAPSHOT`. Checked-in modules include
  `runelite-api` and `runelite-client`, but not an injector, mixin module,
  `runescape-api`/RS interfaces, or the native scheduler implementation.
  Git-tracked file inspection found no `MethodHook`/mixin/injector sources.
- `runelite-client/build.gradle.kts` declares
  `runtimeOnly("net.runelite:injected-client:${project.version}")`.
  Therefore this checkout compiles the public API/client separately and obtains
  the externally built injected client at runtime; it does not build or
  source-edit the injected client.
- `Client.getCallbacks()` and `Callbacks.post(Object)` exist. The concrete
  `Hooks.post` calls `EventBus.post` synchronously; `postDeferred` is explicitly
  delayed. A mutable pre-event is plausible as a *public contract* only if
  native injection calls it before the timing store/task construction.
  Nothing in this checkout supplies that native call or lets it replace the
  four Java method arguments.
- `Client.getActiveMidiRequests()` and `MidiRequest` offer read-only active
  request information; neither provides accepted scheduling timings. The
  earlier `ij.af` name/descriptor belongs solely to the pinned 1.12.39
  injected binary. No current 1.12.40 source mapping to a semantically
  equivalent method is available in this checkout, so this pass does **not**
  claim that name remains valid.

### Why implementation stopped

The requested guarantee is not merely an event carrying four integers: the
native scheduler must receive a single chosen effective four-tuple *before*
its timing store and task graph. An `@MethodHook`-style notification, even if
available in a private injector, cannot be assumed to rewrite argument locals.
The actual current injector/mixin source and mapping are absent from the
official public checkout, so neither argument-replacement support nor a safe
source-level wrapper at the accepted scheduler entry can be verified. A
`runelite-api` event/provider plus `Hooks` implementation would compile but
have no producer at the required native point; tests of such a disconnected
surface would give false confidence. Adding a runtime ASM/Java agent, binary
hash gate, or obfuscated method name to RuneLite would violate the desired
upstream architecture.

Following the user's explicit stop condition, no RuneLite source files were
changed and no API/bridge tests were added. The local upstream branch is clean;
there is no upstream patch to build. A maintainer with access to the current
injected-client source/build must identify the stable scheduler mapping and
confirm whether a source-level `@Replace`/copy or equivalent supported
mechanism can atomically substitute the four values before native task setup.
Only then should a small synchronous public event or provider, native bridge,
and pass-through/mutation/special-route tests be implemented. A synchronous
event matches RuneLite's existing bus but needs explicit multi-writer ordering;
a single provider gives atomic ownership but needs registration/lifecycle.
Neither is usable alone without the native producer. No music policy tuple,
geographic mapping, or login/natural classifier belongs in RuneLite core.
