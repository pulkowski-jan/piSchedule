package org.pischedule.transit;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pischedule.config.AppConfig;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Uses only local HTTP and generated, deliberately tiny GTFS feeds. */
class TransitServiceTest {
    @TempDir Path cache;
    private HttpServer server;
    private String url;
    private final AtomicReference<byte[]> response = new AtomicReference<>();
    private final AtomicReference<String> etag = new AtomicReference<>("\"v1\"");
    private final AtomicReference<String> conditional = new AtomicReference<>();
    private final AtomicInteger code = new AtomicInteger(200);
    private final AtomicInteger requests = new AtomicInteger();
    private volatile CountDownLatch entered;
    private volatile CountDownLatch release;
    private static final Instant MONDAY = Instant.parse("2026-11-09T08:59:00Z");

    @BeforeEach
    void start() throws Exception {
        response.set(zip(baseFeed()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/feed.zip",
                exchange -> {
                    requests.incrementAndGet();
                    conditional.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
                    CountDownLatch ready = entered, wait = release;
                    if (ready != null && wait != null) {
                        ready.countDown();
                        try {
                            wait.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    int result = code.get();
                    if (result == 200 && etag.get().equals(conditional.get())) result = 304;
                    exchange.getResponseHeaders().set("ETag", etag.get());
                    byte[] data = response.get();
                    if (result == 200) {
                        exchange.sendResponseHeaders(200, data.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(data);
                        }
                    } else {
                        exchange.sendResponseHeaders(result, -1);
                        exchange.close();
                    }
                });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/feed.zip";
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private TransitService service(
            List<String> ids,
            List<String> lines,
            Map<String, List<String>> heads,
            Map<String, List<Integer>> directions) {
        return new TransitService(
                new AppConfig.Transit(
                        url, Duration.ofDays(3650), "Łagiewniki", ids, lines, heads, directions),
                cache);
    }

    private TransitService service() {
        return service(List.of(), List.of(), Map.of(), Map.of());
    }

    @Test
    void searchDownloadsWithoutImportAndSelectionIsExact() throws Exception {
        TransitService service = service();
        assertEquals("loading", service.snapshot(MONDAY, 5).status());
        List<Stop> found = service.searchStops("łagiewniki");
        assertEquals(List.of("A", "S", "N"), found.stream().map(Stop::id).toList());
        assertEquals("B", service.searchStops("other platform").get(0).id());
        assertEquals("loading", service.snapshot(MONDAY, 5).status());
        assertEquals(2, requests.get());
        service.update();
        List<Departure> departures = service.snapshot(MONDAY, 3).departures();
        assertEquals(List.of("8", "8", "10"), departures.stream().map(Departure::line).toList());
        assertEquals(List.of("1", "2", "2"), departures.stream().map(Departure::platform).toList());
        assertEquals("Override", departures.get(1).destination());
        assertThrows(UnsupportedOperationException.class, () -> departures.add(departures.get(0)));
    }

    @Test
    void departuresExposeActualStopNamesAndRawPlatformCodes() throws Exception {
        Map<String, String> files = baseFeed();
        files.put(
                "stops.txt",
                "stop_id,stop_name,parent_station,platform_code,stop_code\n"
                        + "S,Łagiewniki,,,\nA,Łagiewniki Platform A,S,553-01,A-CODE\n"
                        + "B,Other platform name,S,553-02,B-CODE\nN,Łagiewniki Nearby,,9,\n");
        response.set(zip(files));
        TransitService service = service();
        service.update();

        TransitSnapshot snapshot = service.snapshot(MONDAY, 3);
        assertEquals("Łagiewniki", snapshot.stopName());
        assertEquals(
                List.of("8", "8", "10"),
                snapshot.departures().stream().map(Departure::line).toList());
        assertEquals(
                List.of("Łagiewniki Platform A", "Other platform name", "Other platform name"),
                snapshot.departures().stream().map(Departure::stopName).toList());
        assertEquals(
                List.of("553-01", "553-02", "553-02"),
                snapshot.departures().stream().map(Departure::platform).toList());
    }

    @Test
    void snapshotsRetainDeparturesThroughExactlySixtySeconds() throws Exception {
        TransitService service = service();
        service.update();
        // Monday 10:00, 10:05 and 10:06 in Warsaw are 09:00, 09:05 and 09:06 UTC.
        Instant scheduled = Instant.parse("2026-11-09T09:00:00Z");
        List<Instant> retained = List.of(scheduled, scheduled.plusSeconds(300));
        for (Instant now :
                List.of(
                        scheduled.minusNanos(1),
                        scheduled,
                        scheduled.plusSeconds(1),
                        scheduled.plusSeconds(59).plusNanos(999_999_999),
                        scheduled.plusSeconds(60))) {
            assertEquals(
                    retained,
                    service.snapshot(now, 2).departures().stream().map(Departure::time).toList(),
                    "snapshot at " + now);
        }
        List<Instant> expired = List.of(scheduled.plusSeconds(300), scheduled.plusSeconds(360));
        for (Instant now :
                List.of(scheduled.plusSeconds(60).plusNanos(1), scheduled.plusSeconds(61))) {
            assertEquals(
                    expired,
                    service.snapshot(now, 2).departures().stream().map(Departure::time).toList(),
                    "snapshot at " + now);
        }
    }

    @Test
    void freshServiceSnapshotIncludesSixtySecondOldDeparture() throws Exception {
        TransitService service = service();
        service.update();
        Departure original = service.snapshot(MONDAY, 1).departures().get(0);
        assertEquals(Instant.parse("2026-11-09T09:00:00Z"), original.time());

        // A new service reads the persisted timetable, not a previously published result.
        TransitService restarted = service();
        Instant boundary = original.time().plusSeconds(60);
        assertEquals(original, restarted.snapshot(boundary, 1).departures().get(0));
        assertEquals(
                Instant.parse("2026-11-09T09:05:00Z"),
                restarted.snapshot(boundary.plusNanos(1), 1).departures().get(0).time());
        assertEquals(1, requests.get());
    }

    @Test
    void krakowExactNamesWithoutParentsUseStopCodesNotZusOrSka() throws Exception {
        Map<String, String> files = baseFeed();
        files.put(
                "stops.txt",
                "stop_id,stop_name,location_type,parent_station,platform_code,stop_code,stop_desc\n"
                        + "stop_314_92219,Łagiewniki,0,,,553-01,01\n"
                        + "stop_314_92229,Łagiewniki,0,,,553-02,02\n"
                        + "stop_314_92249,Łagiewniki,0,,,553-04,04\n"
                        + "stop_314_92259,Łagiewniki,0,,,553-05,05\n"
                        + "zus,Łagiewniki ZUS,0,,,999-01,01\n"
                        + "ska,Łagiewniki SKA,0,,,999-02,02\n");
        files.put(
                "trips.txt",
                "route_id,service_id,trip_id,trip_headsign,direction_id\n"
                        + "r8,s,a,Centre,0\nr8,s,b,Base,1\nr10,s,c,East,0\nr8,s,d,West,0\n"
                        + "r8,s,z1,ZUS,0\nr8,s,z2,SKA,0\n");
        files.put(
                "stop_times.txt",
                "trip_id,departure_time,stop_id,stop_sequence,pickup_type\n"
                        + "z1,10:00:00,zus,1,0\nz2,10:00:00,ska,1,0\n"
                        + "a,10:01:00,stop_314_92219,1,0\nb,10:02:00,stop_314_92229,1,0\n"
                        + "c,10:03:00,stop_314_92249,1,0\nd,10:04:00,stop_314_92259,1,0\n");
        response.set(zip(files));
        TransitService service = service();
        assertEquals(6, service.searchStops("Łagiewniki").size());
        service.update();
        assertEquals(
                List.of("553-01", "553-02", "553-04", "553-05"),
                service.snapshot(MONDAY, 4).departures().stream()
                        .map(Departure::platform)
                        .toList());
    }

    @Test
    void stopDescriptionIsLastPlatformFallback() throws Exception {
        Map<String, String> files = baseFeed();
        files.put(
                "stops.txt",
                "stop_id,stop_name,parent_station,platform_code,stop_code,stop_desc\n"
                        + "S,Łagiewniki,,,,\n"
                        + "A,Łagiewniki,S,,,01\n"
                        + "B,Other platform name,S,,B-CODE,02\n"
                        + "N,Łagiewniki Nearby,,,,\n");
        response.set(zip(files));
        TransitService service = service();
        service.update();
        assertEquals(
                List.of("01", "B-CODE"),
                service.snapshot(MONDAY, 2).departures().stream()
                        .map(Departure::platform)
                        .toList());
    }

    @Test
    void explicitPlatformAndPerLineFiltersDoNotLeakNearbyStopsOrOtherDirections() throws Exception {
        TransitService platform = service(List.of("A"), List.of(), Map.of(), Map.of());
        platform.update();
        List<Departure> one = platform.snapshot(MONDAY, 1).departures();
        assertEquals(1, one.size());
        assertEquals("1", one.get(0).platform());
        assertEquals("Centre", one.get(0).destination());

        TransitService filtered =
                service(
                        List.of(),
                        List.of("8"),
                        Map.of("8", List.of("Override")),
                        Map.of("8", List.of(1)));
        assertEquals("loading", filtered.snapshot(MONDAY, 1).status()); // different cache signature
        filtered.update();
        List<Departure> result = filtered.snapshot(MONDAY, 1).departures();
        assertEquals(1, result.size());
        assertEquals("Override", result.get(0).destination());
        assertEquals("2", result.get(0).platform());
        assertEquals(Instant.parse("2026-11-09T09:05:00Z"), result.get(0).time());
    }

    @Test
    void departuresAreCappedPerDirectionOrFallbackDestinationRatherThanPerLine() throws Exception {
        Map<String, String> files = baseFeed();
        files.remove("calendar.txt");
        files.put("calendar_dates.txt", "service_id,date,exception_type\ns,20261109,1\n");
        StringBuilder trips =
                new StringBuilder("route_id,service_id,trip_id,trip_headsign,direction_id\n");
        StringBuilder stopTimes =
                new StringBuilder(
                        "trip_id,departure_time,stop_id,stop_sequence,stop_headsign,pickup_type\n");
        // Repeated trips for the same line, direction and headsign share one cap.
        addTrips(trips, stopTimes, "centre", "s", "Centre", "0", "", 0, 6);
        addTrips(trips, stopTimes, "moreCentre", "s", "Centre", "0", "", 6, 6);
        addTrips(trips, stopTimes, "return", "s", "Return", "1", "", 20, 4);
        // Missing directions use the effective stop headsign, not the common trip headsign.
        addTrips(trips, stopTimes, "west", "s", "Shared", "", "West", 30, 12);
        addTrips(trips, stopTimes, "east", "s", "Shared", "", "East", 50, 4);
        files.put("trips.txt", trips.toString());
        files.put("stop_times.txt", stopTimes.toString());
        response.set(zip(files));
        TransitService service = service();
        service.update();

        List<Departure> departures =
                service.snapshot(MONDAY, TransitService.MAX_RESULTS).departures();
        assertEquals(28, departures.size());
        assertTrue(departures.stream().allMatch(d -> d.line().equals("8")));
        assertEquals(
                expectedMinutes("2026-11-09T09:00:00Z", 10),
                departures.stream()
                        .filter(d -> Integer.valueOf(0).equals(d.direction()))
                        .map(Departure::time)
                        .toList());
        assertEquals(
                expectedMinutes("2026-11-09T09:20:00Z", 4),
                departures.stream()
                        .filter(d -> Integer.valueOf(1).equals(d.direction()))
                        .map(Departure::time)
                        .toList());
        assertEquals(
                expectedMinutes("2026-11-09T09:30:00Z", 10),
                departures.stream()
                        .filter(d -> d.direction() == null && d.destination().equals("West"))
                        .map(Departure::time)
                        .toList());
        assertEquals(
                expectedMinutes("2026-11-09T09:50:00Z", 4),
                departures.stream()
                        .filter(d -> d.direction() == null && d.destination().equals("East"))
                        .map(Departure::time)
                        .toList());
        // The global limit is still applied chronologically, after the per-group caps.
        assertEquals(departures.subList(0, 13), service.snapshot(MONDAY, 13).departures());
        assertEquals(
                expectedMinutes("2026-11-09T09:20:00Z", 3),
                departures.subList(10, 13).stream().map(Departure::time).toList());
    }

    @Test
    void sameDirectionDifferentDestinationsEachRetainTheirDepartures() throws Exception {
        Map<String, String> files = baseFeed();
        files.remove("calendar.txt");
        files.put("calendar_dates.txt", "service_id,date,exception_type\ns,20261109,1\n");
        StringBuilder trips =
                new StringBuilder("route_id,service_id,trip_id,trip_headsign,direction_id\n");
        StringBuilder stopTimes =
                new StringBuilder(
                        "trip_id,departure_time,stop_id,stop_sequence,stop_headsign,pickup_type\n");
        addTrips(trips, stopTimes, "centre", "s", "Centre", "0", "", 0, 12);
        addTrips(trips, stopTimes, "branch", "s", "Branch", "0", "", 20, 3);
        files.put("trips.txt", trips.toString());
        files.put("stop_times.txt", stopTimes.toString());
        response.set(zip(files));
        TransitService service = service();
        service.update();

        List<Departure> departures =
                service.snapshot(MONDAY, TransitService.MAX_RESULTS).departures();
        assertEquals(
                expectedMinutes("2026-11-09T09:20:00Z", 3),
                departures.stream()
                        .filter(d -> d.destination().equals("Branch"))
                        .map(Departure::time)
                        .toList());
        assertEquals(10, departures.stream().filter(d -> d.destination().equals("Centre")).count());
    }

    @Test
    void groupedSnapshotFindsTwentyFirstGroupBeyondRawDepartureLimit() throws Exception {
        Map<String, String> files = baseFeed();
        files.remove("calendar.txt");
        files.put("calendar_dates.txt", "service_id,date,exception_type\ns,20261109,1\n");
        StringBuilder trips =
                new StringBuilder("route_id,service_id,trip_id,trip_headsign,direction_id\n");
        StringBuilder stopTimes =
                new StringBuilder(
                        "trip_id,departure_time,stop_id,stop_sequence,stop_headsign,pickup_type\n");
        for (int i = 0; i < 20; i++) {
            String name = String.format(Locale.ROOT, "Crowd%02d", i);
            // Effective stop headsigns distinguish these groups despite a shared trip headsign.
            addTrips(trips, stopTimes, name, "s", "Shared", "0", name, 0, i == 0 ? 12 : 10);
        }
        addTrips(trips, stopTimes, "late", "s", "Shared", "0", "Late", 20, 3);
        files.put("trips.txt", trips.toString());
        files.put("stop_times.txt", stopTimes.toString());
        response.set(zip(files));
        TransitService service = service(List.of(), List.of("8"), Map.of(), Map.of());
        service.update();

        List<Departure> raw = service.snapshot(MONDAY, TransitService.MAX_RESULTS).departures();
        assertEquals(200, raw.size());
        assertTrue(raw.stream().noneMatch(d -> d.destination().equals("Late")));
        assertEquals(raw.subList(0, 13), service.snapshot(MONDAY, 13).departures());

        List<Departure> grouped = service.snapshotGroups(MONDAY, 21).departures();
        assertEquals(203, grouped.size());
        assertEquals(21, grouped.stream().map(Departure::destination).distinct().count());
        assertTrue(
                grouped.stream()
                        .allMatch(d ->
                                d.line().equals("8") && Integer.valueOf(0).equals(d.direction())));
        assertEquals(
                grouped.stream().map(Departure::time).sorted().toList(),
                grouped.stream().map(Departure::time).toList());
        for (int i = 0; i < 20; i++) {
            String name = String.format(Locale.ROOT, "Crowd%02d", i);
            assertEquals(
                    expectedMinutes("2026-11-09T09:00:00Z", 10),
                    grouped.stream()
                            .filter(d -> d.destination().equals(name))
                            .map(Departure::time)
                            .toList());
        }
        assertEquals(
                expectedMinutes("2026-11-09T09:20:00Z", 3),
                grouped.stream()
                        .filter(d -> d.destination().equals("Late"))
                        .map(Departure::time)
                        .toList());

        List<Departure> firstTwenty = service.snapshotGroups(MONDAY, 20).departures();
        assertEquals(200, firstTwenty.size());
        assertEquals(20, firstTwenty.stream().map(Departure::destination).distinct().count());
        assertTrue(firstTwenty.stream().noneMatch(d -> d.destination().equals("Late")));
        assertTrue(service.snapshotGroups(MONDAY, 0).departures().isEmpty());
        // All crowd groups have the same first time; any one of them may win the tie.
        List<Departure> tiedFirst = service.snapshotGroups(MONDAY, 1).departures();
        assertEquals(10, tiedFirst.size());
        assertEquals(1, tiedFirst.stream().map(Departure::destination).distinct().count());
        assertTrue(tiedFirst.get(0).destination().startsWith("Crowd"));
        assertEquals(
                expectedMinutes("2026-11-09T09:00:00Z", 10),
                tiedFirst.stream().map(Departure::time).toList());
    }

    @Test
    void groupedSnapshotKeepsLaterDeparturesAndDistinguishesLineAndDirection() throws Exception {
        Map<String, String> files = baseFeed();
        files.remove("calendar.txt");
        files.put("calendar_dates.txt", "service_id,date,exception_type\ns,20261109,1\n");
        files.put(
                "trips.txt",
                "route_id,service_id,trip_id,trip_headsign,direction_id\n"
                        + "r8,s,early,Early,0\nr8,s,later1,Early,0\nr8,s,later2,Early,0\n"
                        + "r8,s,opposite,Early,1\nr10,s,otherLine,Early,0\nr8,s,other,Other,0\n"
                        + "r8,s,expired,Expired first,0\nr8,s,afterExpired,Expired first,0\n");
        files.put(
                "stop_times.txt",
                "trip_id,departure_time,stop_id,stop_sequence,pickup_type\n"
                        + "early,09:59:00,A,1,0\n"
                        + "later1,10:40:00,A,1,0\n"
                        + "later2,10:50:00,A,1,0\n"
                        + "opposite,10:00:00,A,1,0\n"
                        + "otherLine,10:01:00,A,1,0\n"
                        + "other,10:02:00,A,1,0\n"
                        + "expired,09:57:00,A,1,0\n"
                        + "afterExpired,10:55:00,A,1,0\n");
        response.set(zip(files));
        TransitService service = service();
        service.update();

        List<Departure> first = service.snapshotGroups(MONDAY, 1).departures();
        assertEquals(
                List.of(
                        Instant.parse("2026-11-09T08:59:00Z"),
                        Instant.parse("2026-11-09T09:40:00Z"),
                        Instant.parse("2026-11-09T09:50:00Z")),
                first.stream().map(Departure::time).toList());
        assertTrue(
                first.stream()
                        .allMatch(d ->
                                d.line().equals("8")
                                        && d.destination().equals("Early")
                                        && Integer.valueOf(0).equals(d.direction())));
        assertEquals(List.of(first.get(0)), service.snapshot(MONDAY, 1).departures());

        List<Departure> two = service.snapshotGroups(MONDAY, 2).departures();
        assertEquals(
                List.of("8:0", "8:1", "8:0", "8:0"),
                two.stream().map(d -> d.line() + ":" + d.direction()).toList());
        List<Departure> three = service.snapshotGroups(MONDAY, 3).departures();
        assertEquals(
                List.of("8:0", "8:1", "10:0", "8:0", "8:0"),
                three.stream().map(d -> d.line() + ":" + d.direction()).toList());
        assertTrue(three.stream().allMatch(d -> d.destination().equals("Early")));
    }

    @Test
    void laterServiceDaysCanIntroduceNewDirectionsAndFallbackDestinations() throws Exception {
        Map<String, String> files = baseFeed();
        files.remove("calendar.txt");
        files.put(
                "calendar_dates.txt",
                "service_id,date,exception_type\n"
                        + "monday,20261109,1\n"
                        + "tuesday,20261110,1\n"
                        + "wednesday,20261111,1\n"
                        + "thursday,20261112,1\n");
        StringBuilder trips =
                new StringBuilder("route_id,service_id,trip_id,trip_headsign,direction_id\n");
        StringBuilder stopTimes =
                new StringBuilder(
                        "trip_id,departure_time,stop_id,stop_sequence,stop_headsign,pickup_type\n");
        addTrips(trips, stopTimes, "out", "monday", "Out", "0", "", 0, 12);
        addTrips(trips, stopTimes, "back", "tuesday", "Back", "1", "", 0, 3);
        addTrips(trips, stopTimes, "west", "wednesday", "West", "", "", 0, 3);
        addTrips(trips, stopTimes, "east", "thursday", "East", "", "", 0, 3);
        files.put("trips.txt", trips.toString());
        files.put("stop_times.txt", stopTimes.toString());
        response.set(zip(files));
        TransitService service = service();
        service.update();

        // Monday already fills direction 0's quota, but previously unseen groups
        // can still appear on any of the next three service days.
        List<Departure> departures =
                service.snapshot(MONDAY, TransitService.MAX_RESULTS).departures();
        assertEquals(19, departures.size());
        assertEquals(
                expectedMinutes("2026-11-09T09:00:00Z", 10),
                departures.stream()
                        .filter(d -> Integer.valueOf(0).equals(d.direction()))
                        .map(Departure::time)
                        .toList());
        assertEquals(
                expectedMinutes("2026-11-10T09:00:00Z", 3),
                departures.stream()
                        .filter(d -> Integer.valueOf(1).equals(d.direction()))
                        .map(Departure::time)
                        .toList());
        assertEquals(
                expectedMinutes("2026-11-11T09:00:00Z", 3),
                departures.stream()
                        .filter(d -> d.direction() == null && d.destination().equals("West"))
                        .map(Departure::time)
                        .toList());
        assertEquals(
                expectedMinutes("2026-11-12T09:00:00Z", 3),
                departures.stream()
                        .filter(d -> d.direction() == null && d.destination().equals("East"))
                        .map(Departure::time)
                        .toList());
    }

    @Test
    void calendarExceptionsAndPreviousServiceDayAfterMidnight() throws Exception {
        TransitService service = service();
        service.update();
        // Sunday's 25:15 is Monday 01:15 in Warsaw (00:15 UTC, at UTC+1).
        List<Departure> overnight =
                service.snapshot(Instant.parse("2026-11-01T23:00:00Z"), 1).departures();
        assertEquals(Instant.parse("2026-11-02T00:15:00Z"), overnight.get(0).time());
        assertEquals("Night", overnight.get(0).destination());
        // Monday is removed, but the overnight call remains visible for one minute.
        Departure justAfterOvernight =
                service.snapshot(Instant.parse("2026-11-02T00:15:01Z"), 1).departures().get(0);
        assertEquals(Instant.parse("2026-11-02T00:15:00Z"), justAfterOvernight.time());
        assertEquals("Night", justAfterOvernight.destination());
        // Once more than 60 seconds have elapsed, the next service is Tuesday.
        Departure afterOvernight =
                service.snapshot(Instant.parse("2026-11-02T00:16:01Z"), 1).departures().get(0);
        assertEquals(Instant.parse("2026-11-03T09:00:00Z"), afterOvernight.time());
        // Monday is removed, so the next daytime service is on Tuesday.
        Departure next =
                service.snapshot(Instant.parse("2026-11-02T08:59:00Z"), 1).departures().get(0);
        assertEquals(Instant.parse("2026-11-03T09:00:00Z"), next.time());
        assertEquals(
                "expired", service.snapshot(Instant.parse("2027-02-01T00:00:00Z"), 3).status());
        assertTrue(
                service.snapshot(Instant.parse("2027-02-01T00:00:00Z"), 3).departures().isEmpty());
    }

    @Test
    void retentionCrossesMidnightAndIncludesPreviousServiceDay() throws Exception {
        Map<String, String> files = baseFeed();
        files.put(
                "stop_times.txt",
                "trip_id,departure_time,stop_id,stop_sequence,pickup_type\n"
                        + "a,23:59:30,A,1,0\nnight,24:00:00,B,1,0\n");
        response.set(zip(files));
        TransitService service = service();
        service.update();

        // Sunday is added and Monday is removed. Both calls belong to Sunday service,
        // although the 24:00 call occurs on Monday in Warsaw.
        Instant beforeMidnight = Instant.parse("2026-11-01T22:59:30Z");
        Instant midnight = Instant.parse("2026-11-01T23:00:00Z");
        Instant nextService = Instant.parse("2026-11-03T22:59:30Z");
        assertEquals(
                List.of(beforeMidnight, midnight),
                service.snapshot(midnight.plusSeconds(30), 2).departures().stream()
                        .map(Departure::time)
                        .toList());
        assertEquals(
                List.of(midnight, nextService),
                service.snapshot(midnight.plusSeconds(30).plusNanos(1), 2).departures().stream()
                        .map(Departure::time)
                        .toList());
        assertEquals(
                midnight, service.snapshot(midnight.plusSeconds(60), 1).departures().get(0).time());
        assertEquals(
                nextService,
                service.snapshot(midnight.plusSeconds(60).plusNanos(1), 1)
                        .departures()
                        .get(0)
                        .time());
    }

    @Test
    void exceptionOnlyCalendarAndDstUseNoonMinusTwelveElapsedHours() throws Exception {
        Map<String, String> files = baseFeed();
        files.remove("calendar.txt");
        files.put(
                "calendar_dates.txt",
                "service_id,date,exception_type\ns,20260329,1\ns,20261025,1\n");
        files.put(
                "stop_times.txt",
                "trip_id,departure_time,stop_id,stop_sequence,stop_headsign,pickup_type\n"
                        + "a,02:30:00,A,1,,0\n");
        response.set(zip(files));
        TransitService service = service();
        service.update();
        Departure spring =
                service.snapshot(Instant.parse("2026-03-29T00:00:00Z"), 1).departures().get(0);
        assertEquals(Instant.parse("2026-03-29T00:30:00Z"), spring.time());
        Departure autumn =
                service.snapshot(Instant.parse("2026-10-25T00:00:00Z"), 1).departures().get(0);
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), autumn.time());
    }

    @Test
    void conditionalUpdateOfflineRestartAndFailuresPreserveDepartures() throws Exception {
        TransitService service = service();
        service.update();
        TransitSnapshot before = service.snapshot(MONDAY, 3);
        assertEquals("", before.status());
        service.update();
        assertEquals("\"v1\"", conditional.get());
        assertEquals(before.departures(), service.snapshot(MONDAY, 3).departures());
        // A corrupt replacement is rejected and must not replace the working cache.
        etag.set("\"v2\"");
        response.set("not a zip".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, service::update);
        assertEquals("stale", service.snapshot(MONDAY, 3).status());
        assertEquals(before.departures(), service.snapshot(MONDAY, 3).departures());
        TransitService restarted = service();
        assertEquals(before.departures(), restarted.snapshot(MONDAY, 3).departures());
        code.set(503);
        assertThrows(Exception.class, restarted::update);
        assertEquals("stale", restarted.snapshot(MONDAY, 3).status());
        assertEquals(before.departures(), restarted.snapshot(MONDAY, 3).departures());
    }

    @Test
    void corruptSchemaForcesUnconditionalRedownload() throws Exception {
        TransitService service = service();
        service.update();
        Path database;
        try (var files = Files.list(cache)) {
            database =
                    files.filter(path -> path.toString().endsWith(".sqlite"))
                            .findFirst()
                            .orElseThrow();
        }
        try (var connection = TransitService.open(database);
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE departures");
        }
        service.update();
        assertNull(conditional.get());
        assertFalse(service.snapshot(MONDAY, 3).departures().isEmpty());
    }

    @Test
    void expiredReplacementCannotDiscardCurrentlyCoveredCache() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
        Map<String, String> files = baseFeed();
        files.remove("calendar_dates.txt");
        files.put("calendar.txt", calendar(today.minusDays(5), today.plusDays(5)));
        response.set(zip(files));
        TransitService service = service();
        service.update();
        Instant queryTime = today.atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant();
        List<Departure> before = service.snapshot(queryTime, 3).departures();
        etag.set("\"v2\"");
        files.put("calendar.txt", calendar(today.minusDays(40), today.minusDays(30)));
        response.set(zip(files));
        assertThrows(Exception.class, service::update);
        assertEquals(before, service.snapshot(queryTime, 3).departures());
        assertEquals("stale", service.snapshot(queryTime, 3).status());
    }

    private static String calendar(LocalDate start, LocalDate end) {
        return "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
                   + "s,1,1,1,1,1,1,1,"
                + start.format(DateTimeFormatter.BASIC_ISO_DATE)
                + ","
                + end.format(DateTimeFormatter.BASIC_ISO_DATE)
                + "\n";
    }

    @Test
    void failedInitialDownloadIsUnavailableNotLoading() throws Exception {
        code.set(500);
        TransitService service = service();
        assertThrows(Exception.class, service::update);
        assertEquals("unavailable", service.snapshot(MONDAY, 3).status());
        assertTrue(service.snapshot(MONDAY, 3).departures().isEmpty());
    }

    @Test
    void unsupportedFrequencyIsRejectedOnlyWhenItsDeparturesAreSelected() throws Exception {
        Map<String, String> files = baseFeed();
        files.put(
                "frequencies.txt",
                "trip_id,start_time,end_time,headway_secs\nc,09:00:00,11:00:00,600\n");
        response.set(zip(files));
        TransitService all = service();
        assertThrows(Exception.class, all::update);
        assertEquals("unavailable", all.snapshot(MONDAY, 3).status());
        TransitService onlyEight = service(List.of(), List.of("8"), Map.of(), Map.of());
        onlyEight.update();
        assertEquals("8", onlyEight.snapshot(MONDAY, 1).departures().get(0).line());
    }

    @Test
    void missingDataAndAmbiguousAgencyDoNotReplaceValidCache() throws Exception {
        TransitService service = service();
        service.update();
        List<Departure> saved = service.snapshot(MONDAY, 3).departures();
        Map<String, String> files = baseFeed();
        files.remove("trips.txt");
        etag.set("\"v2\"");
        response.set(zip(files));
        assertThrows(Exception.class, service::update);
        assertEquals(saved, service.snapshot(MONDAY, 3).departures());
        files = baseFeed();
        files.put("agency.txt", "agency_id,agency_timezone\na,Europe/Warsaw\nb,Europe/London\n");
        response.set(zip(files));
        assertThrows(Exception.class, service::update);
        assertEquals(saved, service.snapshot(MONDAY, 3).departures());
    }

    @Test
    void feedValidityAndBoardingRestrictionsAreRespected() throws Exception {
        Map<String, String> files = baseFeed();
        files.put(
                "feed_info.txt",
                "feed_publisher_name,feed_start_date,feed_end_date\nCity,20260101,20261109\n");
        files.put(
                "stop_times.txt",
                "trip_id,departure_time,stop_id,stop_sequence,pickup_type\n"
                        + "a,10:00:00,A,1,1\nb,10:05:00,B,1,1\nc,10:06:00,B,1,1\n");
        response.set(zip(files));
        TransitService service = service();
        service.update();
        TransitSnapshot empty = service.snapshot(MONDAY, 3);
        assertEquals("", empty.status());
        assertTrue(empty.departures().isEmpty());
        assertEquals(
                "expired", service.snapshot(Instant.parse("2026-11-10T10:00:00Z"), 3).status());
    }

    @Test
    void invalidCachedTimezoneForcesAnUnconditionalRepair() throws Exception {
        TransitService service = service();
        service.update();
        Path database;
        try (var files = Files.list(cache)) {
            database =
                    files.filter(path -> path.toString().endsWith(".sqlite"))
                            .findFirst()
                            .orElseThrow();
        }
        try (var connection = TransitService.open(database);
                var statement = connection.createStatement()) {
            statement.execute("UPDATE metadata SET timezone='Invalid/Zone'");
        }
        service.update();
        assertNull(conditional.get());
        assertFalse(service.snapshot(MONDAY, 3).departures().isEmpty());
    }

    @Test
    void coverageIsBasedOnFilteredServicesNotExcludedLines() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
        Map<String, String> files = baseFeed();
        files.remove("calendar_dates.txt");
        files.put("trips.txt", files.get("trips.txt").replace("r10,s,c,", "r10,other,c,"));
        String current = calendar(today.minusDays(5), today.plusDays(5));
        String other = current.substring(current.indexOf('\n') + 1).replaceFirst("^s,", "other,");
        files.put("calendar.txt", current + other);
        response.set(zip(files));
        TransitService service = service(List.of(), List.of("8"), Map.of(), Map.of());
        service.update();
        etag.set("\"v2\"");
        files.put("calendar.txt", calendar(today.minusDays(40), today.minusDays(30)) + other);
        response.set(zip(files));
        assertThrows(Exception.class, service::update);
        assertEquals(
                "stale",
                service.snapshot(today.atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant(), 3)
                        .status());
    }

    @Test
    void feedEndBoundsTravelDatesWhileCalendarEndKeepsOvernightService() throws Exception {
        Map<String, String> files = baseFeed();
        files.put(
                "feed_info.txt",
                "feed_publisher_name,feed_start_date,feed_end_date\nCity,20260101,20261109\n");
        response.set(zip(files));
        TransitService bounded = service();
        bounded.update();
        assertTrue(
                bounded.snapshot(Instant.parse("2026-11-09T22:30:00Z"), 3).departures().isEmpty());
        files.remove("feed_info.txt");
        etag.set("\"v2\"");
        response.set(zip(files));
        bounded.update();
        TransitSnapshot overnight = bounded.snapshot(Instant.parse("2026-12-31T23:00:00Z"), 1);
        assertEquals("", overnight.status());
        // 25:15 on December 31 is 01:15 January 1 in Warsaw, or 00:15 UTC.
        assertEquals(Instant.parse("2027-01-01T00:15:00Z"), overnight.departures().get(0).time());
    }

    @Test
    void incompleteReplacementAndConditionalBoardingPreserveWorkingCache() throws Exception {
        TransitService service = service();
        service.update();
        List<Departure> before = service.snapshot(MONDAY, 3).departures();
        etag.set("\"v2\"");
        Map<String, String> files = baseFeed();
        files.put("stop_times.txt", "trip_id,departure_time,stop_id,stop_sequence,pickup_type\n");
        response.set(zip(files));
        assertThrows(Exception.class, service::update);
        assertEquals(before, service.snapshot(MONDAY, 3).departures());
        files.put(
                "stop_times.txt",
                "trip_id,departure_time,stop_id,stop_sequence,pickup_type\na,10:00:00,A,1,2\n");
        response.set(zip(files));
        assertThrows(Exception.class, service::update);
        assertEquals(before, service.snapshot(MONDAY, 3).departures());
    }

    @Test
    void feedStartDoesNotHidePreviousServiceDayAfterMidnight() throws Exception {
        Map<String, String> files = baseFeed();
        files.put(
                "feed_info.txt",
                "feed_publisher_name,feed_start_date,feed_end_date\nCity,20261102,20261231\n");
        response.set(zip(files));
        TransitService service = service();
        service.update();
        Departure overnight =
                service.snapshot(Instant.parse("2026-11-01T23:00:00Z"), 1).departures().get(0);
        assertEquals("Night", overnight.destination());
        // 25:15 Sunday is 01:15 Monday in Warsaw, or 00:15 UTC.
        assertEquals(Instant.parse("2026-11-02T00:15:00Z"), overnight.time());
    }

    @Test
    void badStopIdAndMissingSelectedDepartureTimeAreRejected() throws Exception {
        TransitService wrongStop = service(List.of("missing"), List.of(), Map.of(), Map.of());
        assertThrows(Exception.class, wrongStop::update);
        Map<String, String> files = baseFeed();
        files.put(
                "stop_times.txt",
                "trip_id,departure_time,stop_id,stop_sequence,pickup_type\na,,A,1,0\n");
        response.set(zip(files));
        assertThrows(Exception.class, service()::update);
    }

    @Test
    void snapshotsContinueDuringBlockedDownload() throws Exception {
        TransitService service = service();
        service.update();
        List<Departure> saved = service.snapshot(MONDAY, 3).departures();
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var job =
                    executor.submit(() -> {
                        service.update();
                        return null;
                    });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(saved, service.snapshot(MONDAY, 3).departures());
            release.countDown();
            job.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void addTrips(
            StringBuilder trips,
            StringBuilder stopTimes,
            String prefix,
            String service,
            String headsign,
            String direction,
            String stopHeadsign,
            int firstMinute,
            int count) {
        for (int i = 0; i < count; i++) {
            String id = prefix + i;
            trips.append("r8,")
                    .append(service)
                    .append(',')
                    .append(id)
                    .append(',')
                    .append(headsign)
                    .append(',')
                    .append(direction)
                    .append('\n');
            stopTimes
                    .append(id)
                    .append(',')
                    .append(String.format(Locale.ROOT, "10:%02d:00", firstMinute + i))
                    .append(",A,1,")
                    .append(stopHeadsign)
                    .append(",0\n");
        }
    }

    private static List<Instant> expectedMinutes(String first, int count) {
        Instant start = Instant.parse(first);
        return IntStream.range(0, count).mapToObj(i -> start.plusSeconds(i * 60L)).toList();
    }

    private static Map<String, String> baseFeed() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
                "agency.txt",
                "agency_id,agency_name,agency_url,agency_timezone\n"
                        + "agency,City,http://example.org,Europe/Warsaw\n");
        files.put(
                "stops.txt",
                "stop_id,stop_name,parent_station,platform_code\n"
                        + "S,Łagiewniki,,\n"
                        + "A,Łagiewniki,S,1\n"
                        + "B,Other platform name,S,2\n"
                        + "N,Łagiewniki Nearby,,9\n");
        files.put(
                "routes.txt", "route_id,agency_id,route_short_name\nr8,agency,8\nr10,agency,10\n");
        files.put(
                "trips.txt",
                "route_id,service_id,trip_id,trip_headsign,direction_id\n"
                        + "r8,s,a,Centre,0\nr8,s,b,Base,1\nr10,s,c,East,0\n"
                        + "r8,s,night,Night,0\nr8,s,arrival,No boarding,0\nr8,s,near,Nearby,0\n");
        files.put(
                "stop_times.txt",
                "trip_id,arrival_time,departure_time,stop_id,stop_sequence,stop_headsign,pickup_type\n"
                    + "a,10:00:00,10:00:00,A,1,,0\n"
                    + "b,10:05:00,10:05:00,B,1,Override,0\n"
                    + "c,10:06:00,10:06:00,B,1,,0\n"
                    + "night,25:15:00,25:15:00,B,1,,0\n"
                    + "arrival,10:07:00,10:07:00,A,1,,1\n"
                    + "near,10:01:00,10:01:00,N,1,,0\n");
        files.put(
                "calendar.txt",
                "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
                    + "s,1,1,1,1,1,0,0,20260101,20261231\n");
        files.put(
                "calendar_dates.txt",
                "service_id,date,exception_type\ns,20261101,1\ns,20261102,2\n");
        return files;
    }

    private static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                out.putNextEntry(new ZipEntry(file.getKey()));
                out.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
