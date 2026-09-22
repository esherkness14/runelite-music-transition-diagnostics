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

**Not live-tested in this implementation pass, by request.** Automated tests
exercise the behavior on synthetic methods only; a real-client smoke test loads
and verifies transformed classes but never invokes their game methods. Record
the eventual audible result and probe ordering here without promoting the
signature to a general architecture.
