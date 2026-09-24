# Music Transition Diagnostics: durable agent context

## Project goal

This repository is a temporary reverse-engineering spike toward making OSRS
music transitions smooth. Subjective area tuning now favors a gradual handoff:
the old track fades immediately while the incoming start waits approximately
until that fade completes, then fades in. The candidate **Smooth Defaults**
native tuple is `[outgoingDelay, outgoingFade, incomingDelay, incomingFade] =
[0,200,200,200]`; **Vanilla Timings** are `[0,60,60,0]`. These are scheduler
steps, not milliseconds. Do not describe the preferred tuple as an overlapping
crossfade. **Smooth natural song progression is part of the MVP**, alongside
area/replacement transitions. A separately configured fade on login is a
nice-to-have and must not block the main feature.

Except for an explicitly authorized, opt-in experiment, diagnostics must remain
read-only: do not change playback, volume, varps, varclients, scripts, or other
client state during the investigation. Do not turn a narrow experiment into the
final fade design without new evidence and authorization.

## Preferred direction

Strongly prefer the game/client's existing music-transition machinery and an
early, repeatable client-side signal or hook. Do not build a hard-coded
region/area music map unless investigation demonstrates that no viable
client-side signal or hook exists.

Region IDs are already known to be too coarse for a geographic fallback: the
tested Draynor Manor gate music boundary occurs entirely within one RuneScape
region. If geography ever becomes unavoidable, investigate finer-grained
client data before designing any mapping.

## Confirmed observations

- Natural track progression enters an intermediate state:
  `MUSIC_CURRENT_TRACK` becomes `-1`, `MUSIC_LAST_TRACK` is updated, and roughly
  1.2 seconds later the next current track is assigned.
- At the Draynor Manor gate boundary, `MUSIC_CURRENT_TRACK` changes directly
  from one track to another. There is no observed `-1` stage and no observed
  `MUSIC_LAST_TRACK` update.
- The Draynor Manor boundary is within a single RuneScape region, so region ID
  alone cannot represent the music boundary.
- Live area tuning found `[0,200,200,200]` subjectively produces the gradual
  transition the user wanted. This is a candidate default, not a proven
  universal timing policy or proof of exact audible overlap.
- At least one live teleport into a different music area followed the currently
  overridden replacement path and therefore received the smooth transition.
  This does not establish that all teleports use that path.
- Natural progression remains perceptibly vanilla under the current narrow
  area override. Observed natural scheduling includes a first accepted
  non-jingle request for archive 147 with `[0,20,0,0]`, followed roughly
  1.18 seconds later by a next-song request with `[0,0,0,0]`. Archive 147's
  content and audible role remain unknown. Startup has also accepted
  `[0,0,0,0]`, so that tuple is not a natural-progression classifier.

Treat these as experimental observations, not permanent API contracts. Record
new evidence and revised hypotheses in `RESEARCH-NOTES.md`.

## Development rules

- Always inspect the current official RuneLite source and API before assuming
  that an event, method, generated gameval constant, or game variable still
  exists or behaves the same way.
- Keep diagnostic output bounded. Avoid continuous high-volume logging during
  ordinary play; emit detailed context only around relevant transitions.
- Do not log script stacks or arguments unless a later experiment explicitly
  calls for them.
- Do not submit to RuneLite or Plugin Hub, and do not commit or push unless the
  user explicitly requests it.
- Never commit or inspect `.runelite/credentials.properties`, RuneLite/Jagex
  session/account/token material, or similar secrets. Never search those files
  or include them in diagnostic bundles.
- `.runelite/logs/client.log` may be inspected when explicitly needed for this
  project. Keep inspection focused on diagnostic lines; do not expose incidental
  account/session information. `build/music-core-probe.log` is also an intended
  diagnostic source, not a credential file.
- Experiment 3 authorizes development-only bytecode instrumentation for logging
  the four verified native music entry points. It must preserve all arguments,
  returns, exceptions, and music state; no fade control or intentional delays.
  Keep the agent outside main/plugin artifacts and reject unknown client hashes
  or signatures. See `RESEARCH-NOTES.md` for the exact pinned artifact.
- Experiment 4 established the opt-in `runAreaFadeTest` exception. The current
  Experiment 7 tuning harness permits all four effective timings to be set from
  0–300 native scheduler steps (defaults `[0,60,0,120]`), but only when
  `specialRoute == false` and the complete *original* `ij.af` tuple is exactly
  `[0,60,60,0]`. Ordinary `run` remains observation-only. This signature is a
  controlled experiment, not the final area-detection architecture. Preserve
  the version/hash/signature gates and never alter the natural `0,20,0,0` or
  `0,0,0,0` signatures. All four effective values must come from one guarded
  decision; audit failure must leave all four original values in place.
- Experiment 4 live tests found a probably audible but subtle 60-step incoming
  fade. Archive 151 appeared with `0,0,0,0` in natural progression and
  `0,60,60,0` at an area boundary: timings depend on request context. Leave
  the exact original tuple as a development-only eligibility signature, not
  a production area classifier.
- Experiment 5 live tests confirmed 120-step incoming-fade overrides. The user
  heard a gradual overall transition but not a clearly continuous incoming
  ramp. Experiment 6 confirmed that the verified `nu.az(II)V` stream-volume
  setter does ramp gradually through intermediate values; the sequential feel
  comes from the 60-step incoming delay in `[0,60,60,120]`. Experiment 7 tests
  an overlapping crossfade with default effective tuple `[0,60,0,120]`.
  High-volume setter logging is off by default, available only with explicit
  `-PprobeStreamVolume=true` in the opt-in launcher. Treat setter calls as
  measurements, not proof of perceived loudness. This was an earlier experiment;
  the later preferred area tuple is `[0,200,200,200]`. Login/startup research is
  now explicitly in scope; do not implement or live-test login fading without
  authorization.
- The eventual plugin should have a master enable switch (disabled means **no
  native timing mutation**), four configurable area timings, and distinct
  `Restore Smooth Defaults` (`[0,200,200,200]`) and `Restore Vanilla Timings`
  (`[0,60,60,0]`) actions. An optional, independent login fade is desired;
  200 native steps is its candidate default. Do not build this UI yet.
- The Java agent and obfuscated-name hooks are development-only evidence, not
  the production Plugin Hub architecture. Current public RuneLite API does not
  expose accepted music timing arguments. Investigate the smallest stable
  RuneLite-side **generic accepted-music-scheduling** API/hook without exposing
  obfuscated names or inventing `AREA`, `NATURAL`, `TELEPORT`, or `LOGIN`
  classifications. One scheduler hook sees multiple request origins but needs
  reliable context before the eventual plugin can apply separate replacement
  and natural policies. Preserve the experimental area eligibility; do not
  change natural requests until the two-request sequence is understood.
  `RESEARCH-NOTES.md` records the current static trace and next read-only test.
- Preserve Java 11 compatibility and the official `runelite/example-plugin`
  Gradle structure. Use `./gradlew build` (or `.\gradlew.bat build` on Windows)
  after code changes.
