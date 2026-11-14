package org.pischedule.transit;

import java.util.List;

/** Immutable result suitable for publishing between background and UI threads. */
public record TransitSnapshot(List<Departure> departures, String stopName, String status) {
    public TransitSnapshot {
        departures = List.copyOf(departures);
    }
}
