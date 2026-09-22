package com.esherkness.musictransitiondiagnostics.coreprobe;

import java.nio.file.Files;
import java.nio.file.Path;

/** Forked with -javaagent and -Xverify:all. Never starts RuneLite or executes game methods. */
public final class ProbeSmokeMain
{
	public static void main(String[] args) throws Exception
	{
		Path log = Path.of(System.getProperty("musicCoreProbe.log"));
		if (args.length == 1 && args[0].equals("expect-disabled"))
		{
			if (!Files.readString(log).contains("state=DISABLED"))
			{
				throw new AssertionError("Missing-client probe should be disabled");
			}
			System.out.println("Missing-client smoke: main still runs after disabled premain.");
			return;
		}
		if (!Files.readString(log).contains("state=ARMED"))
		{
			throw new AssertionError("Known-client probe did not arm; inspect " + log);
		}
		for (String name : new String[]{"rj", "ij", "bk", "im", "nu"})
		{
			// Force method verification/resolution but not <clinit> or any music call.
			Class.forName(name, false, ClassLoader.getSystemClassLoader()).getDeclaredMethods();
		}
		String text = Files.readString(log);
		if (text.contains("state=DISABLED"))
		{
			throw new AssertionError("Probe rejected an actual class: " + log);
		}
		for (String method : new String[]{"rj.bc", "ij.af", "bk.ab", "im.as"})
		{
			if (!text.contains("state=TRANSFORMED method=" + method))
			{
				throw new AssertionError("Missing transformation: " + method);
			}
		}
		String nuState = text.contains("mode=AREA_INCOMING_FADE_TEST")
			? "state=TRANSFORMED method=nu.az(II)V"
			: "state=VERIFIED_UNCHANGED method=nu.az(II)V";
		if (!text.contains(nuState))
		{
			throw new AssertionError("Missing native volume-setter verification: " + log);
		}
		System.out.println("Known-client smoke: five target classes verified; no game methods invoked.");
	}
}
