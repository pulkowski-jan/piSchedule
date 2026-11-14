package org.pischedule.transit;

import org.pischedule.config.AppConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Disk-backed scheduled GTFS service. Network and import work must be run off the UI thread.
 */
public final class TransitService {
    private static final long MAX_DOWNLOAD = 256L * 1024 * 1024;
    public static final int MAX_RESULTS = 200;
    private static final int SEARCH_DAYS = 3;
    private static final int PER_DIRECTION = 10;
    private final Path directory;
    private final Path database;
    private final Path archive;
    private final String url;
    private final Duration interval;
    private final String stopName;
    private final List<String> stopIds;
    private final List<String> lines;
    private final Map<String, List<String>> headsigns;
    private final Map<String, List<Integer>> directions;
    private final String signature;
    private final Object databaseLock = new Object();
    private final Object updateLock = new Object();
    private volatile boolean updating;
    private volatile boolean failed;

    public TransitService(AppConfig.Transit config, Path cacheDirectory) {
        Objects.requireNonNull(config, "config");
        directory = Objects.requireNonNull(cacheDirectory, "cacheDirectory").toAbsolutePath();
        url = Objects.requireNonNull(config.url(), "url");
        URI uri = URI.create(url);
        if (!"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("GTFS URL must use HTTP or HTTPS");
        }
        interval = Objects.requireNonNull(config.updateInterval(), "updateInterval");
        if (interval.isNegative() || interval.isZero())
            throw new IllegalArgumentException("updateInterval must be positive");
        stopName = Objects.requireNonNull(config.stopName(), "stopName");
        stopIds = List.copyOf(config.stopIds());
        lines = List.copyOf(config.lines());
        headsigns = copyMap(config.headsigns());
        directions = copyMap(config.directionIds());
        // Length-prefix every value so arbitrary stop names or filter values cannot collide.
        StringBuilder description = new StringBuilder();
        append(description, url);
        append(description, stopName);
        appendValues(description, stopIds);
        appendValues(description, lines);
        appendMap(description, headsigns);
        appendMap(description, directions);
        try {
            signature =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    description
                                                            .toString()
                                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        database = directory.resolve("transit-" + signature + ".sqlite");
        archive = directory.resolve("transit-feed-" + signature + ".zip");
    }

    private static <T> Map<String, List<T>> copyMap(Map<String, List<T>> source) {
        TreeMap<String, List<T>> copy = new TreeMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    private static void append(StringBuilder builder, String value) {
        builder.append(value.length()).append(':').append(value);
    }

    private static <T extends Comparable<? super T>> void appendValues(
            StringBuilder builder, List<T> values) {
        TreeSet<T> sorted = new TreeSet<>(values);
        append(builder, Integer.toString(sorted.size()));
        for (T value : sorted) append(builder, value.toString());
    }

    private static <T extends Comparable<? super T>> void appendMap(
            StringBuilder builder, Map<String, List<T>> values) {
        TreeMap<String, List<T>> sorted = new TreeMap<>(values);
        append(builder, Integer.toString(sorted.size()));
        sorted.forEach((key, list) -> {
            append(builder, key);
            appendValues(builder, list);
        });
    }

    /**
     * Update from the configured URL. A failed update never replaces the last valid database.
     */
    public void update() throws Exception {
        synchronized (updateLock) {
            updating = true;
            Path download = null;
            Path replacement = null;
            try {
                Files.createDirectories(directory);
                try (FileChannel channel =
                                FileChannel.open(
                                        directory.resolve("transit-" + signature + ".lock"),
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.WRITE);
                        FileLock fileLock = tryLock(channel)) {
                    Metadata previous;
                    synchronized (databaseLock) {
                        try {
                            previous = metadata();
                            if (previous != null && !usableCache(database)) previous = null;
                        } catch (Exception invalidCache) {
                            previous = null;
                        }
                    }
                    download = Files.createTempFile(directory, "gtfs-", ".zip.part");
                    Download result =
                            download(
                                    download,
                                    previous == null ? null : previous.etag,
                                    previous == null ? null : previous.modified);
                    if (result.notModified) {
                        if (previous == null || (blank(previous.etag) && blank(previous.modified)))
                            throw new IOException(
                                    "HTTP 304 without a validated conditional request");
                        synchronized (databaseLock) {
                            try (Connection c = open(database);
                                    PreparedStatement p =
                                            c.prepareStatement(
                                                    "UPDATE metadata SET checked_at=?")) {
                                p.setString(1, Instant.now().toString());
                                p.executeUpdate();
                            }
                        }
                    } else {
                        replacement = Files.createTempFile(directory, "transit-", ".sqlite.part");
                        GtfsImporter.importFeed(
                                download,
                                replacement,
                                stopName,
                                stopIds,
                                lines,
                                headsigns,
                                directions,
                                signature,
                                result.etag,
                                result.modified);
                        Metadata next = metadata(replacement);
                        if (next == null || !usableCache(replacement))
                            throw new IOException("Replacement timetable failed validation");
                        Instant now = Instant.now();
                        if (previous != null && covers(previous, now) && !covers(next, now))
                            throw new IOException(
                                    "Replacement timetable does not cover today; retaining the"
                                            + " current cache");
                        synchronized (databaseLock) {
                            atomicReplace(replacement, database);
                            replacement = null;
                        }
                        // The archive is an optional stop-search cache, not the source of the live
                        // timetable.
                        try {
                            atomicReplace(download, archive);
                            download = null;
                        } catch (Exception ignored) {
                            deleteQuietly(archive);
                        }
                    }
                }
                failed = false;
            } catch (Exception e) {
                failed = true;
                throw e;
            } finally {
                updating = false;
                deleteQuietly(download);
                deleteQuietly(replacement);
            }
        }
    }

    /**
     * Search stop names or IDs, case-insensitively, without importing a timetable.
     */
    public List<Stop> searchStops(String query) throws Exception {
        Objects.requireNonNull(query, "query");
        synchronized (updateLock) {
            Files.createDirectories(directory);
            try (FileChannel channel =
                            FileChannel.open(
                                    directory.resolve("transit-" + signature + ".lock"),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE);
                    FileLock fileLock = tryLock(channel)) {
                Path temp = Files.createTempFile(directory, "gtfs-search-", ".zip.part");
                try {
                    download(temp, null, null);
                    // Validate stops before making this the cached archive.
                    List<Stop> stops = GtfsImporter.searchStops(temp, query);
                    atomicReplace(temp, archive);
                    return stops;
                } catch (Exception e) {
                    if (Files.isRegularFile(archive)) {
                        try {
                            List<Stop> cached = GtfsImporter.searchStops(archive, query);
                            System.err.println(
                                    "Stop search: using a possibly stale cached feed ("
                                            + e.getMessage()
                                            + ")");
                            return cached;
                        } catch (Exception invalidArchive) {
                            e.addSuppressed(invalidArchive);
                        }
                    }
                    throw e;
                } finally {
                    deleteQuietly(temp);
                }
            }
        }
    }

    /**
     * Return a bounded immutable snapshot. This does not perform network I/O.
     */
    public TransitSnapshot snapshot(Instant now, int limit) {
        return snapshot(now, limit, false);
    }

    /**
     * Return up to groupLimit line/direction/destination groups, ordered by their
     * earliest departure, including buffered departures for each selected group.
     */
    public TransitSnapshot snapshotGroups(Instant now, int groupLimit) {
        return snapshot(now, groupLimit, true);
    }

    private TransitSnapshot snapshot(Instant now, int limit, boolean groupLimit) {
        Objects.requireNonNull(now, "now");
        if (limit < 0 || limit > MAX_RESULTS)
            throw new IllegalArgumentException("limit must be between 0 and " + MAX_RESULTS);
        synchronized (databaseLock) {
            if (!Files.isRegularFile(database))
                return new TransitSnapshot(
                        List.of(), stopName, failed && !updating ? "unavailable" : "loading");
            try (Connection c = open(database)) {
                // One SQLite read transaction pins the same cache generation for metadata and rows.
                c.setAutoCommit(false);
                Metadata meta = metadata(c);
                if (meta == null)
                    return new TransitSnapshot(
                            List.of(), stopName, failed && !updating ? "unavailable" : "loading");
                ZoneId zone = ZoneId.of(meta.zone);
                LocalDate today = now.atZone(zone).toLocalDate();
                boolean expired = today.isBefore(meta.firstDate) || today.isAfter(meta.lastDate);
                boolean stale = failed || now.isAfter(meta.checked.plus(interval));
                String status =
                        expired ? (stale ? "expired; stale" : "expired") : (stale ? "stale" : "");
                List<Departure> departures =
                        limit == 0
                                ? List.of()
                                : query(c, now, limit, groupLimit, zone, today, meta);
                return new TransitSnapshot(departures, meta.stopName, status);
            } catch (Exception e) {
                return new TransitSnapshot(List.of(), stopName, "unavailable");
            }
        }
    }

    private List<Departure> query(
            Connection c,
            Instant now,
            int limit,
            boolean groupLimit,
            ZoneId zone,
            LocalDate today,
            Metadata meta)
            throws Exception {
        Comparator<Departure> order =
                Comparator.comparing(Departure::time)
                        .thenComparing(Departure::line)
                        .thenComparing(Departure::destination)
                        .thenComparing(
                                Departure::direction,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Departure::stopName)
                        .thenComparing(
                                Departure::platform,
                                Comparator.nullsFirst(Comparator.naturalOrder()));
        // Keep extra departures per direction so the UI can refill rows between snapshots.
        // The head of each queue is the latest retained departure.
        Map<DirectionKey, PriorityQueue<Departure>> groups = new HashMap<>();
        Instant earliestVisible = now.minus(Departure.DISPLAY_GRACE);
        // Feed validity dates describe the actual travel period, not necessarily
        // the service date: 25:00 on the previous day may fall within coverage.
        LocalDate start = today.minusDays(meta.maxSeconds / 86400L + 2);
        LocalDate end =
                today.plusDays(SEARCH_DAYS).isBefore(meta.lastDate)
                        ? today.plusDays(SEARCH_DAYS)
                        : meta.lastDate;
        String sql =
                "SELECT d.seconds, r.line, COALESCE(NULLIF(d.headsign,''),t.headsign), s.platform,"
                    + " t.direction, s.name FROM departures d JOIN trips t ON t.id=d.trip_id JOIN"
                    + " routes r ON r.id=t.route_id JOIN stops s ON s.id=d.stop_id LEFT JOIN"
                    + " calendars c ON c.service_id=t.service_id LEFT JOIN exceptions e ON"
                    + " e.service_id=t.service_id AND e.date=? WHERE COALESCE(e.type, CASE WHEN"
                    + " c.start_date<=? AND c.end_date>=? AND substr(c.weekdays,?,1)='1' THEN 1"
                    + " ELSE 2 END)=1 ORDER BY d.seconds";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                // GTFS defines time as elapsed seconds from local noon minus 12 hours, including on
                // DST days.
                Instant origin = date.atTime(12, 0).atZone(zone).toInstant().minusSeconds(43200);
                // A direction can first appear on a later service day, so scan the full search
                // window.
                String day = GtfsImporter.formatDate(date);
                p.setString(1, day);
                p.setString(2, day);
                p.setString(3, day);
                p.setInt(4, date.getDayOfWeek().getValue());
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) {
                        Instant time = origin.plusSeconds(rs.getInt(1));
                        LocalDate travelDate = time.atZone(zone).toLocalDate();
                        if (time.isBefore(earliestVisible)
                                || travelDate.isBefore(meta.firstDate)
                                || travelDate.isAfter(meta.lastDate)) {
                            continue;
                        }
                        String line = rs.getString(2);
                        String destination = rs.getString(3);
                        Number directionValue = (Number) rs.getObject(5);
                        Integer direction =
                                directionValue == null ? null : directionValue.intValue();
                        if (!allowed(line, destination, direction, lines, headsigns, directions)) {
                            continue;
                        }
                        String boardingStop = rs.getString(6);
                        if (boardingStop == null || boardingStop.isBlank())
                            boardingStop = meta.stopName;
                        Departure departure =
                                new Departure(
                                        line,
                                        destination,
                                        boardingStop,
                                        rs.getString(4),
                                        time,
                                        direction);
                        DirectionKey key = new DirectionKey(line, direction, destination);
                        PriorityQueue<Departure> directionDepartures =
                                groups.computeIfAbsent(
                                        key, k -> new PriorityQueue<>(order.reversed()));
                        directionDepartures.add(departure);
                        if (directionDepartures.size() > PER_DIRECTION) {
                            directionDepartures.poll();
                        }
                    }
                }
            }
        }
        ArrayList<Departure> result = new ArrayList<>();
        if (groupLimit) {
            // Limit by each group's first departure before flattening. Otherwise frequent
            // groups can consume the raw-result limit and hide later departures or groups.
            List<List<Departure>> earliestGroups =
                    groups.values().stream()
                            .map(queue -> queue.stream().sorted(order).toList())
                            .sorted(
                                    Comparator.comparing(
                                            (List<Departure> group) -> group.get(0), order))
                            .limit(limit)
                            .toList();
            earliestGroups.forEach(result::addAll);
        } else {
            groups.values().forEach(result::addAll);
        }
        result.sort(order);
        if (!groupLimit && result.size() > limit) return List.copyOf(result.subList(0, limit));
        return List.copyOf(result);
    }

    private record DirectionKey(String line, Integer direction, String destination) {}

    static boolean allowed(
            String line,
            String destination,
            Integer direction,
            List<String> lines,
            Map<String, List<String>> headsigns,
            Map<String, List<Integer>> directions) {
        if (!lines.isEmpty() && !lines.contains(line)) return false;
        List<String> names = headsigns.get(line);
        if (names != null && !names.isEmpty() && !names.contains(destination)) return false;
        List<Integer> ids = directions.get(line);
        return ids == null || ids.isEmpty() || direction != null && ids.contains(direction);
    }

    static Connection open(Path path) throws Exception {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA cache_size=-2048");
        }
        return c;
    }

    private static boolean covers(Metadata metadata, Instant now) {
        LocalDate day = now.atZone(ZoneId.of(metadata.zone)).toLocalDate();
        return !day.isBefore(metadata.firstDate) && !day.isAfter(metadata.lastDate);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean usableCache(Path path) throws Exception {
        try (Connection c = open(path);
                Statement s = c.createStatement()) {
            try (ResultSet check = s.executeQuery("PRAGMA quick_check(1)")) {
                if (!check.next() || !"ok".equalsIgnoreCase(check.getString(1))) return false;
            }
            // Check all tables and columns used by the departure query, not just metadata.
            try (ResultSet ignored =
                    s.executeQuery(
                            "SELECT d.seconds,d.headsign,d.trip_id,d.stop_id,"
                                + "t.route_id,t.service_id,t.headsign,t.direction,r.line,s.platform,s.name,c.start_date,c.end_date,c.weekdays,e.date,e.type"
                                + " FROM departures d JOIN trips t ON t.id=d.trip_id JOIN routes r"
                                + " ON r.id=t.route_id JOIN stops s ON s.id=d.stop_id LEFT JOIN"
                                + " calendars c ON c.service_id=t.service_id LEFT JOIN exceptions e"
                                + " ON e.service_id=t.service_id LIMIT 0")) {}
            return true;
        }
    }

    private Metadata metadata() throws Exception {
        return metadata(database);
    }

    private Metadata metadata(Path path) throws Exception {
        if (!Files.isRegularFile(path)) return null;
        try (Connection c = open(path)) {
            return metadata(c);
        }
    }

    private Metadata metadata(Connection c) throws Exception {
        try (Statement s = c.createStatement();
                ResultSet r =
                        s.executeQuery(
                                "SELECT signature, timezone, first_date, last_date, max_seconds,"
                                        + " stop_name, checked_at, etag, modified FROM metadata")) {
            if (!r.next() || !signature.equals(r.getString(1))) return null;
            String zone = r.getString(2);
            ZoneId.of(zone);
            LocalDate first = GtfsImporter.parseDate(r.getString(3));
            LocalDate last = GtfsImporter.parseDate(r.getString(4));
            int maximum = r.getInt(5);
            if (first.isAfter(last) || maximum < 0 || maximum > 604799 || r.getString(6) == null)
                throw new IOException("Invalid timetable metadata");
            return new Metadata(
                    zone,
                    first,
                    last,
                    maximum,
                    r.getString(6),
                    Instant.parse(r.getString(7)),
                    r.getString(8),
                    r.getString(9));
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Another timetable update is running");
            return lock;
        } catch (OverlappingFileLockException e) {
            throw new IOException("Another timetable update is running", e);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
        }
    }

    private static void atomicReplace(Path from, Path to) throws IOException {
        try {
            Files.move(
                    from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("Cache filesystem does not support atomic replacement", e);
        }
    }

    private Download download(Path target, String etag, String modified) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "piSchedule/1.0");
                if (etag != null && !etag.isBlank())
                    connection.setRequestProperty("If-None-Match", etag);
                if (modified != null && !modified.isBlank())
                    connection.setRequestProperty("If-Modified-Since", modified);
                int code = connection.getResponseCode();
                if (code == 304) return new Download(true, etag, modified);
                if (code != 200) throw new IOException("GTFS HTTP status " + code);
                if (connection.getContentLengthLong() > MAX_DOWNLOAD)
                    throw new IOException("GTFS archive exceeds size limit");
                try (InputStream in = connection.getInputStream();
                        OutputStream out = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[32768];
                    long total = 0;
                    long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (System.nanoTime() - deadline > 0)
                            throw new IOException("GTFS download timed out");
                        total += n;
                        if (total > MAX_DOWNLOAD)
                            throw new IOException("GTFS archive exceeds size limit");
                        out.write(buffer, 0, n);
                    }
                }
                return new Download(
                        false,
                        connection.getHeaderField("ETag"),
                        connection.getHeaderField("Last-Modified"));
            } catch (IOException e) {
                last = e;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw last;
    }

    private record Download(boolean notModified, String etag, String modified) {}

    private record Metadata(
            String zone,
            LocalDate firstDate,
            LocalDate lastDate,
            int maxSeconds,
            String stopName,
            Instant checked,
            String etag,
            String modified) {}
}
