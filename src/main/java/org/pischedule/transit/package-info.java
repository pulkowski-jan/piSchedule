/**
 * Scheduled GTFS departures, not real-time predictions.
 *
 * <p>{@link org.pischedule.transit.TransitService#update()} and
 * {@link org.pischedule.transit.TransitService#searchStops(String)} perform blocking network/disk
 * work and must not run on the Swing event thread. Snapshots use only the cached SQLite database;
 * a single service instance serializes updates and protects the atomic cache swap against concurrent
 * snapshots. The cache directory must support atomic file replacement. Configuration changes which
 * affect selection use a different cache signature. An unsuccessful update keeps the previous cache,
 * with a stale status (or unavailable if no matching cache exists). Expired and stale can both be
 * reported. The last successful HTTP check is persisted; the failure flag is process-local.
 *
 * <p>Limits: raw snapshots accept 0..200 results; grouped snapshots accept 0..200 groups with
 * up to ten buffered departures per group. SQL bounds restrict departures to the one-minute
 * grace period through the configured look-ahead window (120 minutes by default, at most seven
 * days), while service-date scanning accounts for overnight and DST transitions. GTFS times
 * through 167:59:59 are supported. Downloads are limited to 256 MiB,
 * used ZIP entries to 512 MiB each and 1.5 GiB total, and CSV records to 64 Ki characters. Stop
 * searches return at most 10,000 results and 2 Mi characters (otherwise they fail). Updates use two HTTP attempts,
 * connect/read timeouts and a bounded streaming download. Temporary import and vacuum files require
 * additional free disk space. SQLite native compatibility and actual Pi memory usage remain to be
 * verified on the target device.
 *
 * <p>Frequency trips affecting the configured selection, GTFS flex locations/windows, approximate
 * selected times, continuous pickup at selected routes/stops, and pickup types requiring advance
 * coordination are rejected rather than guessed. Missing required
 * data, unknown configured stop IDs, ambiguous agencies and multiple agency time zones are rejected.
 * For name selection only, exact matching is expanded through station/child relationships; explicit
 * IDs select only those IDs and their descendants. Platform labels prefer platform_code, then stop_code,
 * then stop_desc (which may be descriptive text). Search uses substring matching independently of
 * timetable selection. Search refreshes the archive and falls back to a possibly stale cache when offline.
 */
package org.pischedule.transit;
