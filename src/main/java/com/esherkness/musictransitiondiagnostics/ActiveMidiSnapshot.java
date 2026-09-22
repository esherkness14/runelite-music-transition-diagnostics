package com.esherkness.musictransitiondiagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.runelite.api.MidiRequest;

/** Values only: never keep RuneLite's live list or its mutable request objects. */
final class ActiveMidiSnapshot
{
	private final int archiveId;
	private final boolean isJingle;

	private ActiveMidiSnapshot(int archiveId, boolean isJingle)
	{
		this.archiveId = archiveId;
		this.isJingle = isJingle;
	}

	/** Called on ClientTick, on the client thread. Preserve request order. */
	static List<ActiveMidiSnapshot> copy(List<MidiRequest> requests)
	{
		List<ActiveMidiSnapshot> values = new ArrayList<>(requests.size());
		for (MidiRequest request : requests)
		{
			// Preserve a null slot defensively rather than dereferencing engine state.
			values.add(request == null ? null
				: new ActiveMidiSnapshot(request.getArchiveId(), request.isJingle()));
		}
		return Collections.unmodifiableList(values);
	}

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof ActiveMidiSnapshot))
		{
			return false;
		}
		ActiveMidiSnapshot value = (ActiveMidiSnapshot) other;
		return archiveId == value.archiveId && isJingle == value.isJingle;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(archiveId, isJingle);
	}

	@Override
	public String toString()
	{
		return "{archiveId=" + archiveId + ",isJingle=" + isJingle + "}";
	}
}
