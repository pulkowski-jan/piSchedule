package org.pischedule.transit;

import java.time.Duration;
import java.time.Instant;

/** A scheduled (not real-time) boarding opportunity. */
public record Departure(
        String line,
        String destination,
        String stopName,
        String platform,
        Instant time,
        Integer direction) {
    /** Keep a scheduled departure visible for one minute after its planned time. */
    public static final Duration DISPLAY_GRACE = Duration.ofMinutes(1);

    public boolean isVisibleAt(Instant now) {
        return time != null && !time.isBefore(now.minus(DISPLAY_GRACE));
    }
}
