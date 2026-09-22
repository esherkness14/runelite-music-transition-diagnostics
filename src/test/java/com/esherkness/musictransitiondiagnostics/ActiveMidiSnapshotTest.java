package com.esherkness.musictransitiondiagnostics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.MidiRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActiveMidiSnapshotTest
{
	@Test
	public void copiesValuesInsteadOfKeepingLiveObjects()
	{
		int[] mutableId = {42};
		MidiRequest request = new MidiRequest()
		{
			public int getArchiveId() { return mutableId[0]; }
			public boolean isJingle() { return false; }
		};
		List<MidiRequest> live = new ArrayList<>(Arrays.asList(request));
		List<ActiveMidiSnapshot> first = ActiveMidiSnapshot.copy(live);
		assertEquals(first, ActiveMidiSnapshot.copy(live));
		mutableId[0] = 43;
		assertNotEquals(first, ActiveMidiSnapshot.copy(live));
		live.clear();
		assertEquals("[{archiveId=42,isJingle=false}]", first.toString());
		assertThrows(UnsupportedOperationException.class, first::clear);
	}

	@Test
	public void preservesOrderJingleFlagsAndNullSlots()
	{
		MidiRequest track = request(false);
		MidiRequest jingle = request(true);
		assertNotEquals(ActiveMidiSnapshot.copy(Arrays.asList(track)), ActiveMidiSnapshot.copy(Arrays.asList(jingle)));
		assertNotEquals(ActiveMidiSnapshot.copy(Arrays.asList(track, jingle)), ActiveMidiSnapshot.copy(Arrays.asList(jingle, track)));
		assertEquals("[null]", ActiveMidiSnapshot.copy(Arrays.asList((MidiRequest) null)).toString());
	}

	private static MidiRequest request(boolean jingle)
	{
		return new MidiRequest()
		{
			public int getArchiveId() { return 42; }
			public boolean isJingle() { return jingle; }
		};
	}
}
