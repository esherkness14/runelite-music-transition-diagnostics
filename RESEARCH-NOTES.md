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

## Current hypotheses

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

### Questions for the next test

- Does the same script ID consistently appear immediately before both
  directions of the Draynor Manor boundary crossing?
- Is that script absent from, or differently ordered during, natural
  progression?
- Do the current/remembered ID varps change earlier than
  `MUSIC_CURRENT_TRACK`?
- Is the candidate script specific to opening/updating the music interface, or
  does it plausibly participate in track selection?

### Results

Not yet tested.
