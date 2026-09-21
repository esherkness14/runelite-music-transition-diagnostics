/*
 * BSD 2-Clause License
 *
 * Copyright (c) 2026, esherkness
 * All rights reserved.
 */
package com.esherkness.musictransitiondiagnostics;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * A deliberately small, read-only instrumentation plugin for comparing two
 * kinds of music transition:
 *
 * <ul>
 *     <li>crossing an area boundary that asks the client to change music, and</li>
 *     <li>letting the current track finish while the player remains still.</li>
 * </ul>
 *
 * <p>This is a reverse-engineering spike, not a music-control plugin. Nothing
 * in this class writes a varp, changes the volume, runs a script, or starts or
 * stops a track.</p>
 */
@Slf4j
@PluginDescriptor(
	name = "Music Transition Diagnostics",
	description = "Logs client state around music transitions without changing playback",
	tags = {"music", "diagnostics", "development"}
)
public class MusicTransitionDiagnosticsPlugin extends Plugin
{
	/**
	 * A fixed UTC formatter guarantees an ISO-8601 timestamp with exactly three
	 * fractional digits, including timestamps that fall exactly on a second.
	 */
	private static final DateTimeFormatter TIMESTAMP_FORMAT =
		DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX")
			.withZone(ZoneOffset.UTC);

	private static final String LOG_PREFIX = "[Music Transition Diagnostics]";

	/** Both the look-behind buffer and live follow-up window cover about 500 ms. */
	private static final long SCRIPT_CONTEXT_WINDOW_NANOS = 500_000_000L;
	private static final int SCRIPT_CONTEXT_WINDOW_MILLIS = 500;

	/**
	 * Hard bounds protect normal gameplay from an unbounded event buffer and
	 * protect a transition from producing an arbitrarily large context dump.
	 */
	private static final int MAX_BUFFERED_SCRIPT_EVENTS = 256;
	private static final int MAX_AFTER_SCRIPT_EVENTS = 256;

	@Inject
	private Client client;

	/** Last ClientTick sample. Null means that the next sample emits the baseline. */
	private MusicState previousState;

	/** Recent script IDs are retained in memory but are not normally logged. */
	private final Deque<ScriptObservation> recentScriptEvents = new ArrayDeque<>();

	/** Last current-track value used to avoid duplicate event/tick context dumps. */
	private Integer scriptContextTrackValue;

	/** Non-null only during the approximately 500 ms follow-up logging window. */
	private ScriptContext activeScriptContext;

	@Override
	protected void startUp()
	{
		// Do not carry state or script context across plugin enable cycles.
		previousState = null;
		scriptContextTrackValue = null;
		activeScriptContext = null;
		recentScriptEvents.clear();
		logDiagnostic("LIFECYCLE_START", "state=stopped -> started");
	}

	@Override
	protected void shutDown()
	{
		logDiagnostic("LIFECYCLE_STOP", "state=started -> stopped");
		previousState = null;
		scriptContextTrackValue = null;
		activeScriptContext = null;
		recentScriptEvents.clear();
	}

	/**
	 * VarbitChanged is delivered immediately by RuneLite's event bus. We filter
	 * by the backing varp because these are the only music variables this spike
	 * set out to observe. For a whole-varp change, varbitId is normally -1; if a
	 * varbit backed by one of these varps changes, its actual id is preserved.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!isObservedMusicVarp(event.getVarpId()))
		{
			return;
		}

		logDiagnostic(
			"VARBIT_CHANGED",
			"varpId=" + event.getVarpId()
				+ " varbitId=" + event.getVarbitId()
				+ " value=" + event.getValue());

		if (event.getVarpId() == VarPlayerID.MUSIC_CURRENT_TRACK)
		{
			// Read the whole varp because event.value could represent a child varbit.
			observeCurrentTrackForScriptContext(
				client.getVarpValue(VarPlayerID.MUSIC_CURRENT_TRACK),
				System.nanoTime());
		}
	}

	/**
	 * VarClientIntChanged identifies the changed varclient by index, but does not
	 * carry its new value. Read the value immediately so its event ordering can
	 * be compared with both VarbitChanged and the next ClientTick snapshot.
	 */
	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		if (!isObservedMusicVarClient(event.getIndex()))
		{
			return;
		}

		logDiagnostic(
			"VARCLIENT_INT_CHANGED",
			"index=" + event.getIndex()
				+ " value=" + client.getVarcIntValue(event.getIndex()));
	}

	/** Retain only the script ID and timing; intentionally do not inspect inputs. */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		recordScriptEvent(ScriptPhase.PRE, event.getScriptId());
	}

	/** Retain only the script ID and timing; intentionally do not inspect stacks. */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		recordScriptEvent(ScriptPhase.POST, event.getScriptId());
	}

	/**
	 * ClientTick runs about every 20 ms. Sampling is intentionally cheap. After
	 * one complete baseline line, the plugin writes no polling log line unless
	 * at least one requested value differs from the previous sample. Each field
	 * gets its own line so ordering and timestamps remain easy to compare.
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		long nowNanos = System.nanoTime();
		finishExpiredScriptContext(nowNanos);

		MusicState currentState = readMusicState();

		// The first tick is a single complete baseline, not a set of changes.
		if (previousState == null)
		{
			logDiagnostic("BASELINE", currentState.toDiagnosticString());
			previousState = currentState;
			scriptContextTrackValue = currentState.musicCurrentTrack;
			return;
		}

		logIntChange("VARP_MUSICPLAY", previousState.musicPlay, currentState.musicPlay);
		logIntChange("VARP_MUSICLOOP", previousState.musicLoop, currentState.musicLoop);
		logIntChange("VARP_MUSICMULTI_1", previousState.musicMulti1, currentState.musicMulti1);
		logIntChange("VARP_MUSICMULTI_2", previousState.musicMulti2, currentState.musicMulti2);

		if (previousState.musicCurrentTrack != currentState.musicCurrentTrack)
		{
			logIntChange(
				"VARP_MUSIC_CURRENT_TRACK",
				previousState.musicCurrentTrack,
				currentState.musicCurrentTrack);

			// This is a fallback if no matching VarbitChanged event was delivered.
			observeCurrentTrackForScriptContext(currentState.musicCurrentTrack, nowNanos);
		}

		logIntChange("VARP_MUSIC_LAST_TRACK", previousState.musicLastTrack, currentState.musicLastTrack);
		logIntChange("VARP_MUSIC_OVERRIDE_TRACK", previousState.musicOverrideTrack, currentState.musicOverrideTrack);
		logIntChange("VARP_MUSIC_OVERRIDE_AREA", previousState.musicOverrideArea, currentState.musicOverrideArea);
		logIntChange(
			"VARP_MUSIC_PLAYER_COLOUR_PLAYING",
			previousState.musicPlayerColourPlaying,
			currentState.musicPlayerColourPlaying);
		logIntChange("VARP_MUSIC_CURRENT_ID", previousState.musicCurrentId, currentState.musicCurrentId);
		logIntChange(
			"VARP_MUSIC_CURRENT_ID_REMEMBERED",
			previousState.musicCurrentIdRemembered,
			currentState.musicCurrentIdRemembered);
		logIntChange(
			"VARP_MUSIC_NEXT_ID_REMEMBERED",
			previousState.musicNextIdRemembered,
			currentState.musicNextIdRemembered);
		logIntChange(
			"VARCLIENT_MUSIC_CLIENT_SYNC_TIMER_LAST_INTERVAL",
			previousState.musicClientSyncTimerLastInterval,
			currentState.musicClientSyncTimerLastInterval);
		logIntChange(
			"VARCLIENT_MUSIC_CLIENT_SYNC_TIMER_TIME_PER_INTERVAL",
			previousState.musicClientSyncTimerTimePerInterval,
			currentState.musicClientSyncTimerTimePerInterval);
		logStringChange("NOW_PLAYING_TEXT", previousState.nowPlayingText, currentState.nowPlayingText);
		logIntChange("MUSIC_VOLUME", previousState.musicVolume, currentState.musicVolume);

		previousState = currentState;
	}

	/** Read all music values requested by the experiment without changing them. */
	private MusicState readMusicState()
	{
		return new MusicState(
			client.getVarpValue(VarPlayerID.MUSICPLAY),
			client.getVarpValue(VarPlayerID.MUSICLOOP),
			client.getVarpValue(VarPlayerID.MUSICMULTI_1),
			client.getVarpValue(VarPlayerID.MUSICMULTI_2),
			client.getVarpValue(VarPlayerID.MUSIC_CURRENT_TRACK),
			client.getVarpValue(VarPlayerID.MUSIC_LAST_TRACK),
			client.getVarpValue(VarPlayerID.MUSIC_OVERRIDE_TRACK),
			client.getVarpValue(VarPlayerID.MUSIC_OVERRIDE_AREA),
			client.getVarpValue(VarPlayerID.MUSIC_PLAYER_COLOUR_PLAYING),
			client.getVarpValue(VarPlayerID.MUSIC_CURRENT_ID),
			client.getVarpValue(VarPlayerID.MUSIC_CURRENT_ID_REMEMBERED),
			client.getVarpValue(VarPlayerID.MUSIC_NEXT_ID_REMEMBERED),
			client.getVarcIntValue(VarClientID.MUSIC_CLIENT_SYNC_TIMER_LAST_INTERVAL),
			client.getVarcIntValue(VarClientID.MUSIC_CLIENT_SYNC_TIMER_TIME_PER_INTERVAL),
			readNowPlayingText(),
			client.getMusicVolume());
	}

	private String readNowPlayingText()
	{
		// Use the generated gameval component id rather than a hard-coded widget id.
		Widget nowPlaying = client.getWidget(InterfaceID.Music.NOW_PLAYING);
		return nowPlaying == null ? null : nowPlaying.getText();
	}

	private static boolean isObservedMusicVarp(int varpId)
	{
		return varpId == VarPlayerID.MUSICPLAY
			|| varpId == VarPlayerID.MUSICLOOP
			|| varpId == VarPlayerID.MUSICMULTI_1
			|| varpId == VarPlayerID.MUSICMULTI_2
			|| varpId == VarPlayerID.MUSIC_CURRENT_TRACK
			|| varpId == VarPlayerID.MUSIC_LAST_TRACK
			|| varpId == VarPlayerID.MUSIC_OVERRIDE_TRACK
			|| varpId == VarPlayerID.MUSIC_OVERRIDE_AREA
			|| varpId == VarPlayerID.MUSIC_PLAYER_COLOUR_PLAYING
			|| varpId == VarPlayerID.MUSIC_CURRENT_ID
			|| varpId == VarPlayerID.MUSIC_CURRENT_ID_REMEMBERED
			|| varpId == VarPlayerID.MUSIC_NEXT_ID_REMEMBERED;
	}

	private static boolean isObservedMusicVarClient(int index)
	{
		return index == VarClientID.MUSIC_CLIENT_SYNC_TIMER_LAST_INTERVAL
			|| index == VarClientID.MUSIC_CLIENT_SYNC_TIMER_TIME_PER_INTERVAL;
	}

	/**
	 * Record every script event in memory, but emit it only while a music-track
	 * context window is active. This keeps ordinary gameplay log volume at zero.
	 */
	private void recordScriptEvent(ScriptPhase phase, int scriptId)
	{
		long nowNanos = System.nanoTime();
		finishExpiredScriptContext(nowNanos);

		ScriptObservation observation = new ScriptObservation(
			phase,
			scriptId,
			System.currentTimeMillis(),
			nowNanos,
			client.getTickCount());

		pruneScriptBuffer(nowNanos);
		if (recentScriptEvents.size() == MAX_BUFFERED_SCRIPT_EVENTS)
		{
			recentScriptEvents.removeFirst();
		}
		recentScriptEvents.addLast(observation);

		ScriptContext context = activeScriptContext;
		if (context == null || nowNanos > context.deadlineNanos)
		{
			return;
		}

		if (context.loggedAfterEvents < MAX_AFTER_SCRIPT_EVENTS)
		{
			logScriptObservation("SCRIPT_CONTEXT_AFTER", observation, context);
			context.loggedAfterEvents++;
		}
		else
		{
			context.afterTruncated = true;
		}
	}

	/**
	 * Track changes may be noticed first by VarbitChanged or by ClientTick. A
	 * single remembered value makes either path start exactly one context dump.
	 */
	private void observeCurrentTrackForScriptContext(int newTrack, long changeNanos)
	{
		if (scriptContextTrackValue == null)
		{
			scriptContextTrackValue = newTrack;
			return;
		}

		int oldTrack = scriptContextTrackValue;
		if (oldTrack == newTrack)
		{
			return;
		}

		scriptContextTrackValue = newTrack;
		startScriptContext(oldTrack, newTrack, changeNanos);
	}

	private void startScriptContext(int oldTrack, int newTrack, long changeNanos)
	{
		if (activeScriptContext != null)
		{
			finishScriptContext("superseded-by-next-track-change");
		}

		pruneScriptBuffer(changeNanos);
		ScriptContext context = new ScriptContext(oldTrack, newTrack, changeNanos);

		logDiagnostic(
			"SCRIPT_CONTEXT_BEFORE_BEGIN",
			context.trackDetails()
				+ " windowMs=" + SCRIPT_CONTEXT_WINDOW_MILLIS
				+ " capturedEvents=" + recentScriptEvents.size()
				+ " bufferCapacity=" + MAX_BUFFERED_SCRIPT_EVENTS);

		for (ScriptObservation observation : recentScriptEvents)
		{
			logScriptObservation("SCRIPT_CONTEXT_BEFORE", observation, context);
		}

		logDiagnostic(
			"SCRIPT_CONTEXT_BEFORE_END",
			context.trackDetails() + " capturedEvents=" + recentScriptEvents.size());

		activeScriptContext = context;
		logDiagnostic(
			"SCRIPT_CONTEXT_AFTER_BEGIN",
			context.trackDetails()
				+ " windowMs=" + SCRIPT_CONTEXT_WINDOW_MILLIS
				+ " eventLimit=" + MAX_AFTER_SCRIPT_EVENTS);
	}

	private void pruneScriptBuffer(long nowNanos)
	{
		long cutoffNanos = nowNanos - SCRIPT_CONTEXT_WINDOW_NANOS;
		while (!recentScriptEvents.isEmpty()
			&& recentScriptEvents.peekFirst().nanoTime < cutoffNanos)
		{
			recentScriptEvents.removeFirst();
		}
	}

	private void finishExpiredScriptContext(long nowNanos)
	{
		if (activeScriptContext != null && nowNanos > activeScriptContext.deadlineNanos)
		{
			finishScriptContext("window-complete");
		}
	}

	private void finishScriptContext(String reason)
	{
		ScriptContext context = activeScriptContext;
		if (context == null)
		{
			return;
		}

		activeScriptContext = null;
		logDiagnostic(
			"SCRIPT_CONTEXT_AFTER_END",
			context.trackDetails()
				+ " loggedEvents=" + context.loggedAfterEvents
				+ " truncated=" + context.afterTruncated
				+ " reason=" + reason);
	}

	private void logScriptObservation(
		String eventType,
		ScriptObservation observation,
		ScriptContext context)
	{
		double offsetMillis =
			(observation.nanoTime - context.changeNanos) / 1_000_000.0;

		logDiagnostic(
			eventType,
			context.trackDetails()
				+ " phase=" + observation.phase
				+ " scriptId=" + observation.scriptId
				+ " scriptTimestamp="
				+ TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(observation.timestampMillis))
				+ " offsetMs=" + String.format(Locale.ROOT, "%+.3f", offsetMillis)
				+ " scriptTick=" + observation.tickCount);
	}

	private void logIntChange(String changeType, int oldValue, int newValue)
	{
		if (oldValue != newValue)
		{
			logDiagnostic(changeType, "old=" + oldValue + " -> new=" + newValue);
		}
	}

	private void logStringChange(String changeType, String oldValue, String newValue)
	{
		if (!Objects.equals(oldValue, newValue))
		{
			logDiagnostic(
				changeType,
				"old=" + printable(oldValue) + " -> new=" + printable(newValue));
		}
	}

	/**
	 * Centralizing output makes every diagnostic line carry the same context.
	 * A missing player (login screen/loading) is represented explicitly instead
	 * of suppressing an otherwise useful music event.
	 */
	private void logDiagnostic(String eventType, String details)
	{
		Player localPlayer = client.getLocalPlayer();
		WorldPoint worldPoint = localPlayer == null ? null : localPlayer.getWorldLocation();
		int regionId = worldPoint == null ? -1 : worldPoint.getRegionID();

		log.info(
			"{} timestamp={} tick={} worldPoint={} regionId={} type={} {}",
			LOG_PREFIX,
			TIMESTAMP_FORMAT.format(Instant.now()),
			client.getTickCount(),
			worldPoint == null ? "<unavailable>" : worldPoint,
			regionId,
			eventType,
			details);
	}

	/** Keep widget strings on one physical log line and make null unambiguous. */
	private static String printable(String value)
	{
		if (value == null)
		{
			return "<widget-unavailable>";
		}

		return '"' + value
			.replace("\\", "\\\\")
			.replace("\r", "\\r")
			.replace("\n", "\\n")
			.replace("\"", "\\\"") + '"';
	}

	private enum ScriptPhase
	{
		PRE,
		POST
	}

	/** One compact entry in the bounded in-memory rolling script buffer. */
	private static final class ScriptObservation
	{
		private final ScriptPhase phase;
		private final int scriptId;
		private final long timestampMillis;
		private final long nanoTime;
		private final int tickCount;

		private ScriptObservation(
			ScriptPhase phase,
			int scriptId,
			long timestampMillis,
			long nanoTime,
			int tickCount)
		{
			this.phase = phase;
			this.scriptId = scriptId;
			this.timestampMillis = timestampMillis;
			this.nanoTime = nanoTime;
			this.tickCount = tickCount;
		}
	}

	/** State for one bounded follow-up window associated with a track change. */
	private static final class ScriptContext
	{
		private final int oldTrack;
		private final int newTrack;
		private final long changeNanos;
		private final long deadlineNanos;
		private int loggedAfterEvents;
		private boolean afterTruncated;

		private ScriptContext(int oldTrack, int newTrack, long changeNanos)
		{
			this.oldTrack = oldTrack;
			this.newTrack = newTrack;
			this.changeNanos = changeNanos;
			this.deadlineNanos = changeNanos + SCRIPT_CONTEXT_WINDOW_NANOS;
		}

		private String trackDetails()
		{
			return "trackOld=" + oldTrack + " trackNew=" + newTrack;
		}
	}

	/** Immutable snapshot used only for adjacent ClientTick comparisons. */
	private static final class MusicState
	{
		private final int musicPlay;
		private final int musicLoop;
		private final int musicMulti1;
		private final int musicMulti2;
		private final int musicCurrentTrack;
		private final int musicLastTrack;
		private final int musicOverrideTrack;
		private final int musicOverrideArea;
		private final int musicPlayerColourPlaying;
		private final int musicCurrentId;
		private final int musicCurrentIdRemembered;
		private final int musicNextIdRemembered;
		private final int musicClientSyncTimerLastInterval;
		private final int musicClientSyncTimerTimePerInterval;
		private final String nowPlayingText;
		private final int musicVolume;

		private MusicState(
			int musicPlay,
			int musicLoop,
			int musicMulti1,
			int musicMulti2,
			int musicCurrentTrack,
			int musicLastTrack,
			int musicOverrideTrack,
			int musicOverrideArea,
			int musicPlayerColourPlaying,
			int musicCurrentId,
			int musicCurrentIdRemembered,
			int musicNextIdRemembered,
			int musicClientSyncTimerLastInterval,
			int musicClientSyncTimerTimePerInterval,
			String nowPlayingText,
			int musicVolume)
		{
			this.musicPlay = musicPlay;
			this.musicLoop = musicLoop;
			this.musicMulti1 = musicMulti1;
			this.musicMulti2 = musicMulti2;
			this.musicCurrentTrack = musicCurrentTrack;
			this.musicLastTrack = musicLastTrack;
			this.musicOverrideTrack = musicOverrideTrack;
			this.musicOverrideArea = musicOverrideArea;
			this.musicPlayerColourPlaying = musicPlayerColourPlaying;
			this.musicCurrentId = musicCurrentId;
			this.musicCurrentIdRemembered = musicCurrentIdRemembered;
			this.musicNextIdRemembered = musicNextIdRemembered;
			this.musicClientSyncTimerLastInterval = musicClientSyncTimerLastInterval;
			this.musicClientSyncTimerTimePerInterval = musicClientSyncTimerTimePerInterval;
			this.nowPlayingText = nowPlayingText;
			this.musicVolume = musicVolume;
		}

		/** A single-line dump used only by the first ClientTick BASELINE event. */
		private String toDiagnosticString()
		{
			return "MUSICPLAY=" + musicPlay
				+ " MUSICLOOP=" + musicLoop
				+ " MUSICMULTI_1=" + musicMulti1
				+ " MUSICMULTI_2=" + musicMulti2
				+ " MUSIC_CURRENT_TRACK=" + musicCurrentTrack
				+ " MUSIC_LAST_TRACK=" + musicLastTrack
				+ " MUSIC_OVERRIDE_TRACK=" + musicOverrideTrack
				+ " MUSIC_OVERRIDE_AREA=" + musicOverrideArea
				+ " MUSIC_PLAYER_COLOUR_PLAYING=" + musicPlayerColourPlaying
				+ " MUSIC_CURRENT_ID=" + musicCurrentId
				+ " MUSIC_CURRENT_ID_REMEMBERED=" + musicCurrentIdRemembered
				+ " MUSIC_NEXT_ID_REMEMBERED=" + musicNextIdRemembered
				+ " MUSIC_CLIENT_SYNC_TIMER_LAST_INTERVAL=" + musicClientSyncTimerLastInterval
				+ " MUSIC_CLIENT_SYNC_TIMER_TIME_PER_INTERVAL=" + musicClientSyncTimerTimePerInterval
				+ " NOW_PLAYING_TEXT=" + printable(nowPlayingText)
				+ " MUSIC_VOLUME=" + musicVolume;
		}
	}
}
