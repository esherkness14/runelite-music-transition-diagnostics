package com.esherkness.musictransitiondiagnostics.coreprobe;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;

/** Development-only sink. No client fields, reflection, script inputs, or packets. */
public final class CoreProbeLog
{
	private static final String PREFIX = "[Music Transition Core Probe]";
	private static final DateTimeFormatter UTC = DateTimeFormatter
		.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC);
	private static final StackWalker WALKER = StackWalker.getInstance();
	static final long VOLUME_WINDOW_NANOS = 4_500_000_000L;
	private static final int MAX_VOLUME_LINES = 512;
	private static BufferedWriter file;
	private static volatile boolean enabled;
	private static volatile int configuredIncomingFade = -1;
	private static volatile int configuredIncomingDelay = -1;
	private static volatile boolean probeStreamVolume;
	private static long volumeWindowStart;
	private static int volumeWindowId;
	private static int volumeLineCount;
	private static boolean volumeLimitReported;
	private static boolean disabledReported;

	private CoreProbeLog() { }

	static synchronized void close()
	{
		enabled = false;
		try { if (file != null) { file.close(); } }
		catch (Throwable ignored) { }
		file = null;
	}

	static synchronized void initialize(Path path) throws Exception
	{
		enabled = false;
		configuredIncomingFade = -1;
		configuredIncomingDelay = -1;
		probeStreamVolume = false;
		volumeWindowStart = 0;
		volumeWindowId = 0;
		volumeLineCount = 0;
		volumeLimitReported = false;
		disabledReported = false;
		if (file != null)
		{
			file.close();
		}
		Files.createDirectories(path.toAbsolutePath().getParent());
		// Each premain invocation starts a fresh development log, never appends.
		file = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
	}

	static void arm()
	{
		arm(null);
	}

	static void arm(CoreProbeAgent.AreaFadeSettings settings)
	{
		if (settings != null && (settings.incomingFade < 1
			|| settings.incomingFade > CoreProbeAgent.MAX_INCOMING_FADE
			|| settings.incomingDelay < 0
			|| settings.incomingDelay > CoreProbeAgent.MAX_INCOMING_DELAY))
		{
			throw new IllegalArgumentException("area fade settings out of range");
		}
		configuredIncomingFade = settings == null ? -1 : settings.incomingFade;
		configuredIncomingDelay = settings == null ? -1 : settings.incomingDelay;
		probeStreamVolume = settings != null && settings.probeStreamVolume;
		enabled = true;
		status("ARMED", "version=1.12.39 targets=5 mode="
			+ (settings == null ? "OBSERVE_ONLY" : "AREA_INCOMING_FADE_TEST"
				+ " configuredIncomingFade=" + settings.incomingFade
				+ " configuredIncomingDelay=" + settings.incomingDelay
				+ " probeStreamVolume=" + settings.probeStreamVolume));
	}

	static synchronized void disable(String reason)
	{
		enabled = false;
		if (!disabledReported)
		{
			disabledReported = true;
			status("DISABLED", "reason=" + reason + " clientMayContinue=true");
		}
	}

	static void status(String state, String details)
	{
		try
		{
			write(header(System.nanoTime()) + " state=" + state + " " + details);
		}
		catch (Throwable ignored)
		{
			enabled = false;
			// Never propagate diagnostic I/O failures into RuneLite startup/playback.
			try { System.out.println(PREFIX + " state=DISABLED reason=log-output-failed"); }
			catch (Throwable alsoIgnored) { }
		}
	}

	/**
	 * Called once at entry by the four transformed methods. The integer slots
	 * are copies of JVM locals; nothing is written back. The list is only read.
	 * ij.af logs count only: there is deliberately no obfuscated-field reflection.
	 */
	public static void enter(String method, ArrayList<?> requests,
		int a, int b, int c, int d, int extra)
	{
		try
		{
			if (!enabled)
			{
				return;
			}
			long monoNanos = System.nanoTime();
			String line = header(monoNanos) + " method=" + method;
			switch (method)
			{
				case "rj.bc":
					line += " requestedIds=" + copiedIds(requests) + timings(a, b, c, d);
					break;
				case "ij.af":
					line += " requestCount=" + (requests == null ? "null" : requests.size())
						+ timings(a, b, c, d) + " specialRoute=" + (extra != 0);
					break;
				case "bk.ab":
					line += " outgoingDelay=" + a + " outgoingFade=" + b + " arg3=" + c;
					break;
				case "im.as":
					// Keep raw names: not all of this route's roles were established.
					line += " arg1=" + a + " arg2=" + b + " arg3=" + c
						+ " arg4=" + d + " arg5=" + extra;
					break;
				default:
					return;
			}
			String caller = callerOf(method);
			write(line + " caller=" + caller + " callerCategory=" + category(caller));
		}
		catch (Throwable ignored)
		{
			// Even a surprising argument/logging failure must not affect the game.
			try { disable("event-observation-failed"); }
			catch (Throwable alsoIgnored) { }
		}
	}

	/**
	 * The tuning harness's only behavior-changing decision. This method is injected
	 * only into the opt-in transformer variant. It fails closed: unless the
	 * probe is armed, its mode is enabled, the ordinary route is in use, and
	 * every timing exactly matches the observed area signature, the original
	 * delay/fade pair is returned. The replacement is returned only after its
	 * audit line has been written successfully. A long packs both int values so
	 * the injected method can update both locals from one eligibility decision.
	 */
	public static long effectiveIncomingTimings(int outgoingDelay, int outgoingFade,
		int incomingDelay, int incomingFade, boolean specialRoute)
	{
		int testFade = configuredIncomingFade;
		int testDelay = configuredIncomingDelay;
		if (!enabled || testFade < 1 || specialRoute
			|| outgoingDelay != 0 || outgoingFade != 60
			|| incomingDelay != 60 || incomingFade != 0)
		{
			return packIncomingTimings(incomingDelay, incomingFade);
		}
		try
		{
			long monoNanos = System.nanoTime();
			String caller = callerOf("ij.af");
			synchronized (CoreProbeLog.class)
			{
				int nextWindowId = volumeWindowId + 1;
				write(header(monoNanos)
					+ " method=ij.af override=AREA_INCOMING_FADE"
					+ " originalTimings=[0,60,60,0]"
					+ " effectiveTimings=[0,60," + testDelay + "," + testFade + "]"
					+ " specialRoute=false probeStreamVolume=" + probeStreamVolume
					+ (probeStreamVolume ? " volumeWindow=" + nextWindowId : "")
					+ " caller=" + caller + " callerCategory=" + category(caller));
				if (probeStreamVolume)
				{
					// The optional trace starts only after its audit succeeds.
					volumeWindowId = nextWindowId;
					volumeWindowStart = monoNanos;
					volumeLineCount = 0;
					volumeLimitReported = false;
				}
			}
			return packIncomingTimings(testDelay, testFade);
		}
		catch (Throwable ignored)
		{
			try { disable("override-audit-failed"); }
			catch (Throwable alsoIgnored) { }
			return packIncomingTimings(incomingDelay, incomingFade);
		}
	}

	private static long packIncomingTimings(int delay, int fade)
	{
		return ((long) delay << 32) | (fade & 0xffffffffL);
	}

	/**
	 * Entry observation for the verified nu.az(II)V setter. The setter body
	 * writes its first argument to this stream's volume field. No obfuscated
	 * field or request object is read here; identityHashCode lets us group
	 * calls on the same stream without retaining the stream itself.
	 */
	public static void streamVolumeWrite(Object stream, int requestedVolume)
	{
		if (probeStreamVolume)
		{
			streamVolumeWriteAt(stream, requestedVolume, System.nanoTime());
		}
	}

	// The explicit clock also lets fixture tests verify expiry without sleeping.
	static void streamVolumeWriteAt(Object stream, int requestedVolume, long monoNanos)
	{
		if (!enabled || !probeStreamVolume || configuredIncomingFade < 1)
		{
			return;
		}
		try
		{
			synchronized (CoreProbeLog.class)
			{
				long elapsedNanos = monoNanos - volumeWindowStart;
				if (volumeWindowId == 0 || elapsedNanos < 0 || elapsedNanos >= VOLUME_WINDOW_NANOS)
				{
					return;
				}
				if (volumeLineCount >= MAX_VOLUME_LINES)
				{
					if (!volumeLimitReported)
					{
						volumeLimitReported = true;
						write(header(monoNanos) + " type=STREAM_VOLUME_LIMIT volumeWindow="
							+ volumeWindowId + " maxLines=" + MAX_VOLUME_LINES);
					}
					return;
				}
				String caller = callerOf("nu.az");
				write(header(monoNanos) + " type=STREAM_VOLUME_WRITE volumeWindow="
					+ volumeWindowId + " elapsedNanos=" + elapsedNanos
					+ " streamIdentity=0x" + Integer.toHexString(System.identityHashCode(stream))
					+ " requestedVolume=" + requestedVolume + " caller=" + caller
					+ " task=" + musicTask(caller));
				volumeLineCount++;
			}
		}
		catch (Throwable ignored)
		{
			try { disable("volume-observation-failed"); }
			catch (Throwable alsoIgnored) { }
		}
	}

	static String musicTask(String caller)
	{
		if (caller.startsWith("wp.")) { return "FadeInTask"; }
		if (caller.startsWith("wo.")) { return "FadeOutTask"; }
		if (caller.startsWith("wk.")) { return "StartSongTask"; }
		return "OTHER";
	}

	private static String copiedIds(ArrayList<?> requests)
	{
		if (requests == null)
		{
			return "null";
		}
		Object[] ids = requests.toArray();
		for (int i = 0; i < ids.length; i++)
		{
			// Do not invoke toString on arbitrary client objects.
			if (ids[i] != null && !(ids[i] instanceof Integer))
			{
				ids[i] = "<non-integer>";
			}
		}
		return Arrays.toString(ids);
	}

	private static String callerOf(String method)
	{
		// Walk only until the immediate caller is found; never emit a full stack.
		return WALKER.walk(frames -> frames
			.dropWhile(f -> !(f.getClassName() + "." + f.getMethodName()).equals(method))
			.skip(1).findFirst().map(f -> f.getClassName() + "." + f.getMethodName())
			.orElse("<unavailable>"));
	}

	static String category(String caller)
	{
		if (caller.equals("client.ia") || caller.equals("client.mh"))
		{
			return "PACKET";
		}
		return caller.equals("ee.bt") ? "SCRIPT" : "OTHER";
	}

	private static String timings(int a, int b, int c, int d)
	{
		return " outgoingDelay=" + a + " outgoingFade=" + b
			+ " incomingDelay=" + c + " incomingFade=" + d;
	}

	private static String header(long nanoTime)
	{
		return PREFIX + " timestamp=" + UTC.format(Instant.now()) + " nanoTime=" + nanoTime;
	}

	private static synchronized void write(String line) throws Exception
	{
		System.out.println(line);
		if (file != null)
		{
			file.write(line);
			file.newLine();
			file.flush();
		}
	}
}
