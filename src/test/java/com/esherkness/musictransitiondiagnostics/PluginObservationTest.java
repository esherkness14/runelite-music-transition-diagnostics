package com.esherkness.musictransitiondiagnostics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.MidiRequest;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

public class PluginObservationTest
{
	@Test
	public void baselineThenOnlyChangedSnapshotsAndMonotonicMetadata() throws Exception
	{
		List<MidiRequest> live = new ArrayList<>();
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) -> {
				switch (method.getName())
				{
					case "getActiveMidiRequests": return live;
					case "getVarpValue": case "getVarcIntValue": case "getMusicVolume": case "getTickCount": return 0;
					case "getLocalPlayer": case "getWidget": return null;
					default: throw new AssertionError("Unexpected client call: " + method.getName());
				}
			});
		MusicTransitionDiagnosticsPlugin plugin = new MusicTransitionDiagnosticsPlugin();
		Field field = MusicTransitionDiagnosticsPlugin.class.getDeclaredField("client");
		field.setAccessible(true);
		field.set(plugin, client);
		Logger logger = (Logger) LoggerFactory.getLogger(MusicTransitionDiagnosticsPlugin.class);
		Level previousLevel = logger.getLevel();
		ListAppender<ILoggingEvent> lines = new ListAppender<>();
		lines.start();
		logger.setLevel(Level.INFO);
		logger.addAppender(lines);
		try
		{
			plugin.onClientTick(null);
			assertEquals(1, lines.list.size());
			assertTrue(lines.list.get(0).getFormattedMessage().contains("type=BASELINE"));
			assertTrue(lines.list.get(0).getFormattedMessage().contains("ACTIVE_MIDI_REQUESTS=[]"));
			plugin.onClientTick(null);
			assertEquals(1, lines.list.size());
			live.add(new MidiRequest()
			{
				public int getArchiveId() { return 88; }
				public boolean isJingle() { return true; }
			});
			plugin.onClientTick(null);
			plugin.onClientTick(null);
			assertEquals(2, lines.list.size());
			assertTrue(lines.list.get(1).getFormattedMessage().contains(
				"type=ACTIVE_MIDI_REQUESTS old=[] -> new=[{archiveId=88,isJingle=true}]"));
			live.clear();
			plugin.onClientTick(null);
			assertEquals(3, lines.list.size());
			assertTrue(lines.list.get(2).getFormattedMessage().contains("old=[{archiveId=88,isJingle=true}] -> new=[]"));
			for (ILoggingEvent line : lines.list)
			{
				assertTrue(line.getFormattedMessage().matches(".* monoNanos=-?\\d+ tick=.*"));
				assertFalse(line.getFormattedMessage().contains("SCRIPT_CONTEXT"));
			}
			plugin.shutDown();
			plugin.startUp();
			plugin.onClientTick(null);
			assertTrue(lines.list.get(lines.list.size() - 1).getFormattedMessage().contains("type=BASELINE"));
		}
		finally
		{
			logger.detachAppender(lines);
			logger.setLevel(previousLevel);
			lines.stop();
		}
	}
}
