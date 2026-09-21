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
import java.util.Objects;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
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
 * in this class writes a varp, changes the volume, or starts/stops a track.</p>
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

	@Inject
	private Client client;

	/** Last ClientTick sample. Null means that the next sample establishes a baseline. */
	private MusicState previousState;

	@Override
	protected void startUp()
	{
		// Do not carry a comparison baseline across plugin disable/enable cycles.
		previousState = null;
		logDiagnostic("LIFECYCLE_START", "state=stopped -> started");
	}

	@Override
	protected void shutDown()
	{
		logDiagnostic("LIFECYCLE_STOP", "state=started -> stopped");
		previousState = null;
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
	}

	/**
	 * ClientTick runs about every 20 ms. Sampling is intentionally cheap, and
	 * the plugin writes no polling log line unless at least one requested value
	 * differs from the previous sample. Each field gets its own line so ordering
	 * and timestamps remain easy to compare during the experiment.
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		MusicState currentState = readMusicState();

		// The first tick is a silent baseline, not a transition.
		if (previousState == null)
		{
			previousState = currentState;
			return;
		}

		logIntChange("VARP_MUSICPLAY", previousState.musicPlay, currentState.musicPlay);
		logIntChange("VARP_MUSICLOOP", previousState.musicLoop, currentState.musicLoop);
		logIntChange("VARP_MUSICMULTI_1", previousState.musicMulti1, currentState.musicMulti1);
		logIntChange("VARP_MUSICMULTI_2", previousState.musicMulti2, currentState.musicMulti2);
		logStringChange("NOW_PLAYING_TEXT", previousState.nowPlayingText, currentState.nowPlayingText);
		logIntChange("MUSIC_VOLUME", previousState.musicVolume, currentState.musicVolume);

		previousState = currentState;
	}

	/** Read only the six values requested by the experiment. */
	private MusicState readMusicState()
	{
		return new MusicState(
			client.getVarpValue(VarPlayerID.MUSICPLAY),
			client.getVarpValue(VarPlayerID.MUSICLOOP),
			client.getVarpValue(VarPlayerID.MUSICMULTI_1),
			client.getVarpValue(VarPlayerID.MUSICMULTI_2),
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
			|| varpId == VarPlayerID.MUSICMULTI_2;
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

	/** Immutable snapshot used only for adjacent ClientTick comparisons. */
	private static final class MusicState
	{
		private final int musicPlay;
		private final int musicLoop;
		private final int musicMulti1;
		private final int musicMulti2;
		private final String nowPlayingText;
		private final int musicVolume;

		private MusicState(
			int musicPlay,
			int musicLoop,
			int musicMulti1,
			int musicMulti2,
			String nowPlayingText,
			int musicVolume)
		{
			this.musicPlay = musicPlay;
			this.musicLoop = musicLoop;
			this.musicMulti1 = musicMulti1;
			this.musicMulti2 = musicMulti2;
			this.nowPlayingText = nowPlayingText;
			this.musicVolume = musicVolume;
		}
	}
}

