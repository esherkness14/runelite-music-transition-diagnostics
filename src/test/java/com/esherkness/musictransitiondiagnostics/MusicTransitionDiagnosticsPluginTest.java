package com.esherkness.musictransitiondiagnostics;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Development-client entry point used by the Gradle run task. */
public class MusicTransitionDiagnosticsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(MusicTransitionDiagnosticsPlugin.class);
		RuneLite.main(args);
	}
}

