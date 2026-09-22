# Music Transition Diagnostics: durable agent context

## Project goal

This repository is a temporary reverse-engineering spike toward making OSRS
music transitions smoothly fade out, pause briefly, and fade in. The eventual
behavior should ideally cover both area-triggered music changes and natural
end-of-track progression.

Do **not** implement fading until the client transition path is understood.
Diagnostics must remain read-only: do not change playback, volume, varps,
varclients, scripts, or other client state during the investigation.

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
- Preserve Java 11 compatibility and the official `runelite/example-plugin`
  Gradle structure. Use `./gradlew build` (or `.\gradlew.bat build` on Windows)
  after code changes.
