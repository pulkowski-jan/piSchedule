package org.pischedule.transit;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Streaming importer. Temporary SQLite tables keep feed-sized data out of the Java heap. */
final class GtfsImporter {
    private static final long ENTRY_LIMIT = 512L * 1024 * 1024;
    private static final long TOTAL_LIMIT = 1536L * 1024 * 1024;
    private static final int MAX_ENTRIES = 1000;
    private static final int MAX_SELECTED_STOPS = 50000;
    private static final int BATCH_SIZE = 100;
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final CSVFormat CSV =
            CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();

    private GtfsImporter() {}

    static LocalDate parseDate(String value) {
        return LocalDate.parse(value, DATE);
    }

    static String formatDate(LocalDate value) {
        return DATE.format(value);
    }

    static void importFeed(
            Path zip,
            Path db,
            String name,
            List<String> ids,
            List<String> lines,
            Map<String, List<String>> headsigns,
            Map<String, List<Integer>> directions,
            String signature,
            String etag,
            String modified)
            throws Exception {
        try (Feed feed = new Feed(zip);
                Connection c = TransitService.open(db)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA temp_store=FILE");
                s.execute(
                        "CREATE TABLE metadata(signature TEXT, timezone TEXT, first_date TEXT,"
                                + " last_date TEXT, max_seconds INTEGER, stop_name TEXT, checked_at"
                                + " TEXT, etag TEXT, modified TEXT)");
                s.execute(
                        "CREATE TABLE stops(id TEXT PRIMARY KEY, name TEXT NOT NULL, parent TEXT"
                                + " NOT NULL, platform TEXT NOT NULL)");
                s.execute("CREATE TABLE selected(id TEXT PRIMARY KEY)");
                s.execute(
                        "CREATE TABLE routes(id TEXT PRIMARY KEY, line TEXT NOT NULL,"
                                + " continuous_pickup TEXT NOT NULL)");
                s.execute(
                        "CREATE TABLE trips(id TEXT PRIMARY KEY, route_id TEXT NOT NULL, service_id"
                                + " TEXT NOT NULL, headsign TEXT NOT NULL, direction INTEGER)");
                s.execute(
                        "CREATE TABLE departures(trip_id TEXT NOT NULL, stop_id TEXT NOT NULL,"
                            + " seconds INTEGER NOT NULL, headsign TEXT NOT NULL, sequence INTEGER"
                            + " NOT NULL, PRIMARY KEY(trip_id,sequence))");
                s.execute(
                        "CREATE TABLE calendars(service_id TEXT PRIMARY KEY, weekdays TEXT NOT"
                                + " NULL, start_date TEXT NOT NULL, end_date TEXT NOT NULL)");
                s.execute(
                        "CREATE TABLE exceptions(service_id TEXT NOT NULL, date TEXT NOT NULL, type"
                                + " INTEGER NOT NULL, PRIMARY KEY(service_id,date))");
            }
            Agencies agencies = agency(feed);
            loadStops(feed, c);
            Set<String> selected = selectStops(c, name, ids);
            loadRoutes(feed, c, agencies);
            loadTrips(feed, c);
            loadCalendars(feed, c);
            int maxSeconds = loadDepartures(feed, c, selected);
            try (Statement s = c.createStatement()) {
                s.execute("CREATE INDEX departures_trip ON departures(trip_id)");
                s.execute("CREATE INDEX trips_service ON trips(service_id)");
            }
            validateSelected(c);
            checkFrequencies(feed, c, lines, headsigns, directions);
            String[] coverage = coverage(c, lines, headsigns, directions);
            // Calendar end dates refer to service days; the final service can run after midnight.
            coverage[1] =
                    formatDate(
                            parseDate(coverage[1]).plusDays(Integer.parseInt(coverage[2]) / 86400));
            constrainFeedCoverage(feed, coverage);
            try (PreparedStatement p =
                    c.prepareStatement("INSERT INTO metadata VALUES(?,?,?,?,?,?,?,?,?)")) {
                p.setString(1, signature);
                p.setString(2, agencies.zone());
                p.setString(3, coverage[0]);
                p.setString(4, coverage[1]);
                p.setInt(5, maxSeconds);
                p.setString(6, name);
                p.setString(7, Instant.now().toString());
                p.setString(8, etag);
                p.setString(9, modified);
                p.executeUpdate();
            }
            // Only dependencies of retained departures are needed at query time.
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM trips WHERE id NOT IN (SELECT trip_id FROM departures)");
                s.execute("DELETE FROM routes WHERE id NOT IN (SELECT route_id FROM trips)");
                s.execute(
                        "DELETE FROM calendars WHERE service_id NOT IN (SELECT service_id FROM"
                                + " trips)");
                s.execute(
                        "DELETE FROM exceptions WHERE service_id NOT IN (SELECT service_id FROM"
                                + " trips)");
                s.execute("DELETE FROM stops WHERE id NOT IN (SELECT id FROM selected)");
                s.execute("DROP TABLE selected");
                s.execute("CREATE INDEX departures_seconds ON departures(seconds)");
            }
            c.commit();
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("VACUUM");
            }
        }
    }

    static List<Stop> searchStops(Path zip, String query) throws Exception {
        String needle = query.toLowerCase(Locale.ROOT);
        List<Stop> result = new ArrayList<>();
        int resultChars = 0;
        try (Feed feed = new Feed(zip);
                CSVParser rows = feed.csv("stops.txt", true, "stop_id", "stop_name")) {
            for (CSVRecord row : rows) {
                checkRow(row, "stops.txt");
                String id = required(row, "stop_id"), name = stopName(row);
                if (id.toLowerCase(Locale.ROOT).contains(needle)
                        || name.toLowerCase(Locale.ROOT).contains(needle)) {
                    String parent = optional(row, "parent_station"), platform = platform(row);
                    resultChars +=
                            id.length() + name.length() + parent.length() + platform.length();
                    if (result.size() == 10000 || resultChars > 2 * 1024 * 1024)
                        throw new IOException(
                                "Stop search has too many results; use a more specific query");
                    result.add(new Stop(id, name, parent, platform));
                }
            }
        }
        result.sort(Comparator.comparing(Stop::name).thenComparing(Stop::id));
        return List.copyOf(result);
    }

    private record Agencies(String zone, Set<String> ids, int count) {}

    private static Agencies agency(Feed feed) throws Exception {
        String zone = null;
        Set<String> ids = new HashSet<>();
        int count = 0, missing = 0;
        try (CSVParser rows = feed.csv("agency.txt", true, "agency_timezone")) {
            for (CSVRecord row : rows) {
                checkRow(row, "agency.txt");
                if (++count > 10000) throw new IOException("Too many agencies");
                String value = required(row, "agency_timezone");
                ZoneId.of(value);
                if (zone != null && !zone.equals(value))
                    throw new IOException("Multiple agency time zones are unsupported");
                zone = value;
                String id = optional(row, "agency_id");
                if (id.isEmpty()) missing++;
                else if (!ids.add(id)) throw new IOException("Duplicate agency_id: " + id);
            }
        }
        if (zone == null) throw new IOException("No agency timezone");
        if (count > 1 && missing != 0)
            throw new IOException("Multiple agencies require agency IDs");
        return new Agencies(zone, Set.copyOf(ids), count);
    }

    private static void loadStops(Feed feed, Connection c) throws Exception {
        try (CSVParser rows = feed.csv("stops.txt", true, "stop_id", "stop_name");
                PreparedStatement p = c.prepareStatement("INSERT INTO stops VALUES(?,?,?,?)")) {
            int count = 0;
            for (CSVRecord row : rows) {
                checkRow(row, "stops.txt");
                p.setString(1, required(row, "stop_id"));
                p.setString(2, stopName(row));
                p.setString(3, optional(row, "parent_station"));
                p.setString(4, platform(row));
                p.addBatch();
                if (++count % BATCH_SIZE == 0) {
                    p.executeBatch();
                    p.clearBatch();
                }
            }
            p.executeBatch();
        }
    }

    private static String stopName(CSVRecord row) throws IOException {
        String type = optional(row, "location_type");
        if (!type.isEmpty() && !List.of("0", "1", "2", "3", "4").contains(type))
            throw new IOException("Invalid stop location_type: " + type);
        return type.equals("3") || type.equals("4")
                ? optional(row, "stop_name")
                : required(row, "stop_name");
    }

    private static String platform(CSVRecord row) {
        String value = optional(row, "platform_code");
        if (value.isEmpty()) value = optional(row, "stop_code");
        return value.isEmpty() ? optional(row, "stop_desc") : value;
    }

    private static Set<String> selectStops(Connection c, String name, List<String> ids)
            throws Exception {
        if (ids.isEmpty()) {
            try (PreparedStatement p =
                    c.prepareStatement(
                            "INSERT OR IGNORE INTO selected SELECT id FROM stops WHERE name=?")) {
                p.setString(1, name);
                p.executeUpdate();
            }
            // If an exact name matches a platform, include the other children of its parent
            // station.
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "INSERT OR IGNORE INTO selected SELECT parent FROM stops WHERE id IN"
                            + " (SELECT id FROM selected) AND parent<>'' AND parent IN (SELECT id"
                            + " FROM stops)");
            }
        } else {
            try (PreparedStatement find = c.prepareStatement("SELECT 1 FROM stops WHERE id=?");
                    PreparedStatement insert =
                            c.prepareStatement("INSERT OR IGNORE INTO selected VALUES(?)")) {
                for (String id : ids) {
                    find.setString(1, id);
                    try (ResultSet r = find.executeQuery()) {
                        if (!r.next()) throw new IOException("Configured stop ID not found: " + id);
                    }
                    insert.setString(1, id);
                    insert.executeUpdate();
                }
            }
        }
        try (Statement s = c.createStatement()) {
            s.executeUpdate(
                    "WITH RECURSIVE children(id) AS (SELECT id FROM selected UNION SELECT s.id FROM"
                            + " stops s JOIN children c ON s.parent=c.id) INSERT OR IGNORE INTO"
                            + " selected SELECT id FROM children");
        }
        try (Statement s = c.createStatement();
                ResultSet r =
                        s.executeQuery(
                                "SELECT id FROM stops WHERE id IN (SELECT id FROM selected) AND"
                                        + " parent<>'' AND parent NOT IN (SELECT id FROM stops)"
                                        + " LIMIT 1")) {
            if (r.next())
                throw new IOException(
                        "Selected stop has an unknown parent station: " + r.getString(1));
        }
        Set<String> selected = new HashSet<>();
        long selectedChars = 0;
        try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT id FROM selected")) {
            while (r.next()) {
                String id = r.getString(1);
                selectedChars += id.length();
                if (selected.size() >= MAX_SELECTED_STOPS || selectedChars > 2L * 1024 * 1024)
                    throw new IOException("Too many or excessively long selected stop IDs");
                selected.add(id);
            }
        }
        if (selected.isEmpty()) throw new IOException("No exact GTFS stop matches: " + name);
        return selected;
    }

    private static void loadRoutes(Feed feed, Connection c, Agencies agencies) throws Exception {
        try (CSVParser rows = feed.csv("routes.txt", true, "route_id");
                PreparedStatement p = c.prepareStatement("INSERT INTO routes VALUES(?,?,?)")) {
            int count = 0;
            for (CSVRecord row : rows) {
                checkRow(row, "routes.txt");
                String agencyId = optional(row, "agency_id");
                if ((agencyId.isEmpty() && agencies.count() > 1)
                        || (!agencyId.isEmpty() && !agencies.ids().contains(agencyId)))
                    throw new IOException("Missing or unknown route agency_id");
                String line = optional(row, "route_short_name");
                if (line.isEmpty()) line = optional(row, "route_long_name");
                String continuous = optional(row, "continuous_pickup");
                if (!continuous.isEmpty() && !List.of("0", "1", "2", "3").contains(continuous))
                    throw new IOException("Invalid route continuous_pickup");
                p.setString(1, required(row, "route_id"));
                p.setString(2, line);
                p.setString(3, continuous.isEmpty() ? "1" : continuous);
                p.addBatch();
                if (++count % BATCH_SIZE == 0) {
                    p.executeBatch();
                    p.clearBatch();
                }
            }
            p.executeBatch();
        }
    }

    private static void loadTrips(Feed feed, Connection c) throws Exception {
        try (CSVParser rows = feed.csv("trips.txt", true, "route_id", "service_id", "trip_id");
                PreparedStatement p = c.prepareStatement("INSERT INTO trips VALUES(?,?,?,?,?)")) {
            int count = 0;
            for (CSVRecord row : rows) {
                checkRow(row, "trips.txt");
                p.setString(1, required(row, "trip_id"));
                p.setString(2, required(row, "route_id"));
                p.setString(3, required(row, "service_id"));
                p.setString(4, optional(row, "trip_headsign"));
                String direction = optional(row, "direction_id");
                if (direction.isEmpty()) p.setNull(5, Types.INTEGER);
                else if (direction.equals("0") || direction.equals("1"))
                    p.setInt(5, Integer.parseInt(direction));
                else throw new IOException("Invalid direction_id: " + direction);
                p.addBatch();
                if (++count % BATCH_SIZE == 0) {
                    p.executeBatch();
                    p.clearBatch();
                }
            }
            p.executeBatch();
        }
    }

    private static void loadCalendars(Feed feed, Connection c) throws Exception {
        if (feed.has("calendar.txt")) {
            try (CSVParser rows =
                            feed.csv(
                                    "calendar.txt",
                                    true,
                                    "service_id",
                                    "monday",
                                    "tuesday",
                                    "wednesday",
                                    "thursday",
                                    "friday",
                                    "saturday",
                                    "sunday",
                                    "start_date",
                                    "end_date");
                    PreparedStatement p =
                            c.prepareStatement("INSERT INTO calendars VALUES(?,?,?,?)")) {
                int count = 0;
                for (CSVRecord row : rows) {
                    checkRow(row, "calendar.txt");
                    StringBuilder days = new StringBuilder();
                    for (String col :
                            List.of(
                                    "monday",
                                    "tuesday",
                                    "wednesday",
                                    "thursday",
                                    "friday",
                                    "saturday",
                                    "sunday")) {
                        String flag = required(row, col);
                        if (!flag.equals("0") && !flag.equals("1"))
                            throw new IOException("Invalid calendar weekday");
                        days.append(flag);
                    }
                    String start = required(row, "start_date"), end = required(row, "end_date");
                    if (parseDate(start).isAfter(parseDate(end)))
                        throw new IOException("Calendar ends before it starts");
                    p.setString(1, required(row, "service_id"));
                    p.setString(2, days.toString());
                    p.setString(3, start);
                    p.setString(4, end);
                    p.addBatch();
                    if (++count % BATCH_SIZE == 0) {
                        p.executeBatch();
                        p.clearBatch();
                    }
                }
                p.executeBatch();
            }
        }
        if (feed.has("calendar_dates.txt")) {
            try (CSVParser rows =
                            feed.csv(
                                    "calendar_dates.txt",
                                    true,
                                    "service_id",
                                    "date",
                                    "exception_type");
                    PreparedStatement p =
                            c.prepareStatement("INSERT INTO exceptions VALUES(?,?,?)")) {
                int count = 0;
                for (CSVRecord row : rows) {
                    checkRow(row, "calendar_dates.txt");
                    String date = required(row, "date"), type = required(row, "exception_type");
                    parseDate(date);
                    if (!type.equals("1") && !type.equals("2"))
                        throw new IOException("Invalid calendar exception type");
                    p.setString(1, required(row, "service_id"));
                    p.setString(2, date);
                    p.setInt(3, Integer.parseInt(type));
                    p.addBatch();
                    if (++count % BATCH_SIZE == 0) {
                        p.executeBatch();
                        p.clearBatch();
                    }
                }
                p.executeBatch();
            }
        }
        if (!feed.has("calendar.txt") && !feed.has("calendar_dates.txt"))
            throw new IOException("Missing service calendar");
    }

    private static int loadDepartures(Feed feed, Connection c, Set<String> selected)
            throws Exception {
        int max = 0, count = 0, selectedCalls = 0;
        try (CSVParser rows =
                        feed.csv("stop_times.txt", true, "trip_id", "stop_id", "stop_sequence");
                PreparedStatement p =
                        c.prepareStatement("INSERT INTO departures VALUES(?,?,?,?,?)")) {
            for (CSVRecord row : rows) {
                checkRow(row, "stop_times.txt");
                String stop = optional(row, "stop_id");
                if (stop.isEmpty()
                        || !optional(row, "location_group_id").isEmpty()
                        || !optional(row, "location_id").isEmpty())
                    throw new IOException("GTFS flexible location stop times are unsupported");
                if (!selected.contains(stop)) continue;
                selectedCalls++;
                if (!optional(row, "start_pickup_drop_off_window").isEmpty()
                        || !optional(row, "end_pickup_drop_off_window").isEmpty())
                    throw new IOException("GTFS pickup windows are unsupported");
                String pickup = optional(row, "pickup_type");
                if (!pickup.isEmpty() && !List.of("0", "1", "2", "3").contains(pickup))
                    throw new IOException("Invalid pickup_type");
                // Arrival-only calls are not departures. Conditional boarding needs an
                // explicit presentation; rejecting it avoids silently hiding service.
                if (pickup.equals("1")) continue;
                if (pickup.equals("2") || pickup.equals("3"))
                    throw new IOException(
                            "Selected service requires pickup coordination, which is unsupported");
                String continuous = optional(row, "continuous_pickup");
                if (!continuous.isEmpty() && !continuous.equals("1"))
                    throw new IOException("Continuous pickup is unsupported");
                String precision = optional(row, "timepoint");
                if (!precision.isEmpty() && !precision.equals("1"))
                    throw new IOException("Approximate stop times are unsupported");
                String sequence = required(row, "stop_sequence");
                if (!sequence.matches("[0-9]{1,9}"))
                    throw new IOException("Invalid stop_sequence: " + sequence);
                int seconds = time(required(row, "departure_time"));
                max = Math.max(max, seconds);
                p.setString(1, required(row, "trip_id"));
                p.setString(2, stop);
                p.setInt(3, seconds);
                p.setString(4, optional(row, "stop_headsign"));
                p.setInt(5, Integer.parseInt(sequence));
                p.addBatch();
                if (++count % BATCH_SIZE == 0) {
                    p.executeBatch();
                    p.clearBatch();
                }
            }
            p.executeBatch();
        }
        if (selectedCalls == 0) throw new IOException("No stop times found for the selected stops");
        return max;
    }

    private static int time(String value) throws IOException {
        if (!value.matches("[0-9]{1,3}:[0-9]{2}:[0-9]{2}"))
            throw new IOException("Invalid GTFS time: " + value);
        String[] parts = value.split(":");
        int h = Integer.parseInt(parts[0]),
                m = Integer.parseInt(parts[1]),
                s = Integer.parseInt(parts[2]);
        if (h > 167 || m > 59 || s > 59) throw new IOException("Unsupported GTFS time: " + value);
        return h * 3600 + m * 60 + s;
    }

    private static void validateSelected(Connection c) throws Exception {
        try (Statement s = c.createStatement();
                ResultSet r =
                        s.executeQuery(
                                "SELECT d.trip_id FROM departures d LEFT JOIN trips t ON"
                                    + " t.id=d.trip_id LEFT JOIN routes r ON r.id=t.route_id WHERE"
                                    + " t.id IS NULL OR r.id IS NULL OR r.line='' OR"
                                    + " COALESCE(NULLIF(d.headsign,''),t.headsign)='' OR (NOT"
                                    + " EXISTS (SELECT 1 FROM calendars c WHERE"
                                    + " c.service_id=t.service_id) AND NOT EXISTS (SELECT 1 FROM"
                                    + " exceptions e WHERE e.service_id=t.service_id AND e.type=1))"
                                    + " LIMIT 1")) {
            if (r.next())
                throw new IOException(
                        "Missing route, trip, destination, or service for selected trip "
                                + r.getString(1));
        }
        try (Statement s = c.createStatement();
                ResultSet r =
                        s.executeQuery(
                                "SELECT d.trip_id FROM departures d JOIN trips t ON t.id=d.trip_id"
                                        + " JOIN routes r ON r.id=t.route_id WHERE"
                                        + " r.continuous_pickup<>'1' LIMIT 1")) {
            if (r.next())
                throw new IOException(
                        "Continuous route pickup is unsupported for selected trip "
                                + r.getString(1));
        }
    }

    private static void checkFrequencies(
            Feed feed,
            Connection c,
            List<String> lines,
            Map<String, List<String>> headsigns,
            Map<String, List<Integer>> directions)
            throws Exception {
        if (!feed.has("frequencies.txt")) return;
        try (CSVParser rows = feed.csv("frequencies.txt", true, "trip_id");
                PreparedStatement p =
                        c.prepareStatement(
                                "SELECT r.line, COALESCE(NULLIF(d.headsign,''),t.headsign),"
                                        + " t.direction FROM departures d JOIN trips t ON"
                                        + " t.id=d.trip_id JOIN routes r ON r.id=t.route_id WHERE"
                                        + " d.trip_id=?")) {
            for (CSVRecord row : rows) {
                checkRow(row, "frequencies.txt");
                p.setString(1, required(row, "trip_id"));
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        Number directionValue = (Number) r.getObject(3);
                        Integer direction =
                                directionValue == null ? null : directionValue.intValue();
                        if (TransitService.allowed(
                                r.getString(1),
                                r.getString(2),
                                direction,
                                lines,
                                headsigns,
                                directions))
                            throw new IOException("Frequency-based selected trips are unsupported");
                    }
                }
            }
        }
    }

    private static String[] coverage(
            Connection c,
            List<String> lines,
            Map<String, List<String>> headsigns,
            Map<String, List<Integer>> directions)
            throws Exception {
        // The validity warning must describe services that actually pass the configured filters.
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TEMP TABLE eligible_services(id TEXT PRIMARY KEY)");
        }
        boolean hasTrips;
        try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT EXISTS(SELECT 1 FROM departures)")) {
            hasTrips = r.next() && r.getInt(1) == 1;
        }
        boolean hasEligible = false;
        int eligibleMaxSeconds = 0;
        try (Statement s = c.createStatement();
                ResultSet r =
                        s.executeQuery(
                                "SELECT"
                                    + " t.service_id,r.line,COALESCE(NULLIF(d.headsign,''),t.headsign),t.direction,MAX(d.seconds)"
                                    + " FROM departures d JOIN trips t ON t.id=d.trip_id JOIN"
                                    + " routes r ON r.id=t.route_id GROUP BY"
                                    + " t.service_id,r.line,COALESCE(NULLIF(d.headsign,''),t.headsign),t.direction");
                PreparedStatement insert =
                        c.prepareStatement("INSERT OR IGNORE INTO eligible_services VALUES(?)")) {
            while (r.next()) {
                Number value = (Number) r.getObject(4);
                Integer direction = value == null ? null : value.intValue();
                if (TransitService.allowed(
                        r.getString(2), r.getString(3), direction, lines, headsigns, directions)) {
                    insert.setString(1, r.getString(1));
                    insert.executeUpdate();
                    eligibleMaxSeconds = Math.max(eligibleMaxSeconds, r.getInt(5));
                    hasEligible = true;
                }
            }
        }
        if (hasTrips && !hasEligible)
            throw new IOException("No departures match the configured filters");
        String selected = "SELECT id FROM eligible_services";
        String condition = hasEligible ? " WHERE service_id IN (" + selected + ")" : "";
        String sql =
                "SELECT MIN(start_date), MAX(end_date) FROM (SELECT start_date, end_date FROM"
                        + " calendars"
                        + condition
                        + " UNION ALL SELECT date, date FROM exceptions WHERE type=1"
                        + (hasEligible ? " AND service_id IN (" + selected + ")" : "")
                        + ")";
        try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            if (!r.next() || r.getString(1) == null || r.getString(2) == null)
                throw new IOException("Feed has no service-date coverage");
            return new String[] {
                r.getString(1), r.getString(2), Integer.toString(eligibleMaxSeconds)
            };
        }
    }

    private static void constrainFeedCoverage(Feed feed, String[] coverage) throws Exception {
        if (!feed.has("feed_info.txt")) return;
        try (CSVParser rows = feed.csv("feed_info.txt", true)) {
            int count = 0;
            for (CSVRecord row : rows) {
                checkRow(row, "feed_info.txt");
                if (++count > 1)
                    throw new IOException("Multiple feed_info records are unsupported");
                String start = optional(row, "feed_start_date"),
                        end = optional(row, "feed_end_date");
                if (!start.isEmpty()) {
                    parseDate(start);
                    if (start.compareTo(coverage[0]) > 0) coverage[0] = start;
                }
                if (!end.isEmpty()) {
                    parseDate(end);
                    if (end.compareTo(coverage[1]) < 0) coverage[1] = end;
                }
            }
        }
        if (coverage[0].compareTo(coverage[1]) > 0)
            throw new IOException("Feed validity and service calendars do not overlap");
    }

    private static String required(CSVRecord row, String key) throws IOException {
        String value = optional(row, key);
        if (value.isEmpty())
            throw new IOException(
                    "Missing required " + key + " at CSV record " + row.getRecordNumber());
        return value;
    }

    private static String optional(CSVRecord row, String key) {
        return row.isMapped(key) && row.isSet(key) ? row.get(key).trim() : "";
    }

    private static void checkRow(CSVRecord row, String name) throws IOException {
        if (!row.isConsistent())
            throw new IOException("Malformed " + name + " record " + row.getRecordNumber());
    }

    /** Limit a whole CSV record, including quoted multiline fields, before the parser allocates it. */
    private static final class RecordLimitReader extends java.io.FilterReader {
        private static final int LIMIT = 64 * 1024;
        private int recordLength;
        private boolean quoted;

        RecordLimitReader(java.io.Reader input) {
            super(input);
        }

        private void accept(char ch) throws IOException {
            if (++recordLength > LIMIT) throw new IOException("GTFS CSV record exceeds size limit");
            if (ch == '"') quoted = !quoted;
            if ((ch == '\n' || ch == '\r') && !quoted) recordLength = 0;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0) accept((char) value);
            return value;
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            for (int i = 0; i < n; i++) accept(buffer[offset + i]);
            return n;
        }
    }

    private static final class Feed implements AutoCloseable {
        private final ZipFile zip;
        private final Map<String, ZipEntry> entries = new HashMap<>();
        private long total;

        Feed(Path path) throws Exception {
            zip = new ZipFile(path.toFile());
            try {
                var enumeration = zip.entries();
                int count = 0;
                while (enumeration.hasMoreElements()) {
                    ZipEntry entry = enumeration.nextElement();
                    if (++count > MAX_ENTRIES) throw new IOException("Too many ZIP entries");
                    if (entries.putIfAbsent(entry.getName(), entry) != null)
                        throw new IOException("Duplicate ZIP entry: " + entry.getName());
                }
            } catch (Exception e) {
                zip.close();
                throw e;
            }
        }

        boolean has(String name) {
            return entries.containsKey(name);
        }

        CSVParser csv(String name, boolean required, String... columns) throws Exception {
            ZipEntry entry = entries.get(name);
            if (entry == null || entry.isDirectory()) {
                if (required) throw new IOException("Missing GTFS file " + name);
                return null;
            }
            if (entry.getSize() > ENTRY_LIMIT)
                throw new IOException("GTFS file too large: " + name);
            InputStream raw = zip.getInputStream(entry);
            InputStream limited =
                    new FilterInputStream(raw) {
                        long count;
                        boolean verified;
                        final java.util.zip.CRC32 crc = new java.util.zip.CRC32();

                        private void add(long n) throws IOException {
                            if (n > 0 && ((count += n) > ENTRY_LIMIT || (total += n) > TOTAL_LIMIT))
                                throw new IOException("Uncompressed GTFS size limit exceeded");
                        }

                        private void verify() throws IOException {
                            if (!verified
                                    && ((entry.getSize() >= 0 && entry.getSize() != count)
                                            || (entry.getCrc() >= 0
                                                    && entry.getCrc() != crc.getValue())))
                                throw new IOException("Corrupt GTFS ZIP entry: " + name);
                            verified = true;
                        }

                        @Override
                        public int read() throws IOException {
                            int b = in.read();
                            if (b < 0) verify();
                            else {
                                add(1);
                                crc.update(b);
                            }
                            return b;
                        }

                        @Override
                        public int read(byte[] b, int off, int len) throws IOException {
                            int n = in.read(b, off, len);
                            if (n < 0) verify();
                            else if (n > 0) {
                                add(n);
                                crc.update(b, off, n);
                            }
                            return n;
                        }
                    };
            BufferedReader reader =
                    new BufferedReader(
                            new RecordLimitReader(
                                    new InputStreamReader(
                                            limited,
                                            StandardCharsets.UTF_8
                                                    .newDecoder()
                                                    .onMalformedInput(
                                                            java.nio.charset.CodingErrorAction
                                                                    .REPORT)
                                                    .onUnmappableCharacter(
                                                            java.nio.charset.CodingErrorAction
                                                                    .REPORT))));
            try {
                reader.mark(1);
                if (reader.read() != '\ufeff') reader.reset();
                CSVParser parser = CSV.parse(reader);
                for (String column : columns) {
                    if (!parser.getHeaderMap().containsKey(column)) {
                        parser.close();
                        throw new IOException("Missing " + column + " in " + name);
                    }
                }
                return parser;
            } catch (Exception e) {
                reader.close();
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }
}
