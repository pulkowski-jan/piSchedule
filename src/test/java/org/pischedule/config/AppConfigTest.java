package org.pischedule.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

class AppConfigTest {
    @TempDir Path temporaryDirectory;

    private AppConfig load(String text) throws IOException {
        Path path = temporaryDirectory.resolve("config.toml");
        Files.writeString(path, text);
        return AppConfig.load(path);
    }

    @Test
    void defaultsForAnEmptyFile() throws IOException {
        AppConfig config = load("");
        assertEquals("https://gtfs.ztp.krakow.pl/GTFS_KRK_T.zip", config.transit().url());
        assertEquals(Duration.ofDays(1), config.transit().updateInterval());
        assertEquals("Łagiewniki", config.transit().stopName());
        assertEquals(List.of(), config.transit().stopIds());
        assertEquals(List.of(), config.transit().lines());
        assertEquals(Map.of(), config.transit().headsigns());
        assertEquals(Map.of(), config.transit().directionIds());
        assertEquals(50.0647, config.weather().latitude());
        assertEquals(19.9450, config.weather().longitude());
        assertEquals("Kraków", config.weather().location());
        assertEquals("celsius", config.weather().units());
        assertEquals(Duration.ofMinutes(15), config.weather().refreshInterval());
        assertEquals(ZoneId.of("Europe/Warsaw"), config.display().timeZone());
        assertEquals("HH:mm", config.display().timeFormat());
        assertTrue(config.display().fullScreen());
        assertEquals(1.0, config.display().textScale());
        assertEquals(6, config.display().departureLimit());
        assertEquals(temporaryDirectory.resolve("cache"), config.cacheDirectory());
    }

    @Test
    void checkedInExampleMatchesDefaults() throws IOException {
        AppConfig example = AppConfig.load(Path.of("config.example.toml"));
        AppConfig defaults = load("");
        assertEquals(defaults.transit(), example.transit());
        assertEquals(defaults.weather(), example.weather());
        assertEquals(defaults.display(), example.display());
        assertEquals(
                Path.of("config.example.toml").toAbsolutePath().getParent().resolve("cache"),
                example.cacheDirectory());
    }

    @Test
    void readsCustomizedSettingsIncludingPerLineFilters() throws IOException {
        AppConfig config =
                load(
                        """
                        cache_directory = "../store"
                        [transit]
                        url = "https://example.org/feed.zip"
                        update_interval_minutes = 30
                        stop_name = "Station"
                        stop_ids = ["p1", "p2"]
                        lines = ["8", "50.1"]
                        [transit.headsigns]
                        "8" = ["Destination A", "Destination B"]
                        "50.1" = []
                        [transit.direction_ids]
                        "8" = [0, 1]
                        [weather]
                        latitude = -90
                        longitude = 180
                        location = "Elsewhere"
                        units = "fahrenheit"
                        refresh_interval_minutes = 60
                        [display]
                        time_zone = "UTC"
                        time_format = "h:mm a"
                        full_screen = false
                        text_scale = 1.5
                        departure_limit = 12
                        """);
        assertEquals(temporaryDirectory.resolve("../store").normalize(), config.cacheDirectory());
        assertEquals("https://example.org/feed.zip", config.transit().url());
        assertEquals(Duration.ofMinutes(30), config.transit().updateInterval());
        assertEquals("Station", config.transit().stopName());
        assertEquals(List.of("p1", "p2"), config.transit().stopIds());
        assertEquals(List.of("8", "50.1"), config.transit().lines());
        assertEquals(
                Map.of("8", List.of("Destination A", "Destination B"), "50.1", List.of()),
                config.transit().headsigns());
        assertEquals(Map.of("8", List.of(0, 1)), config.transit().directionIds());
        assertEquals(-90, config.weather().latitude());
        assertEquals(180, config.weather().longitude());
        assertEquals("Elsewhere", config.weather().location());
        assertEquals("fahrenheit", config.weather().units());
        assertEquals(Duration.ofMinutes(60), config.weather().refreshInterval());
        assertEquals(ZoneId.of("UTC"), config.display().timeZone());
        assertEquals("h:mm a", config.display().timeFormat());
        assertFalse(config.display().fullScreen());
        assertEquals(1.5, config.display().textScale());
        assertEquals(12, config.display().departureLimit());
    }

    @Test
    void absoluteCachePathIsNotResolvedAgainstConfig() throws IOException {
        Path absolute = temporaryDirectory.resolve("absolute");
        AppConfig config = load("cache_directory = \"" + absolute + "\"\n");
        assertEquals(absolute, config.cacheDirectory());
    }

    @Test
    void transitDefensivelyCopiesNestedCollections() {
        List<String> stops = new ArrayList<>(List.of("one"));
        List<String> destinations = new ArrayList<>(List.of("Center"));
        Map<String, List<String>> headsigns = new LinkedHashMap<>();
        headsigns.put("8", destinations);
        List<Integer> ids = new ArrayList<>(List.of(0));
        Map<String, List<Integer>> directions = new LinkedHashMap<>();
        directions.put("8", ids);
        AppConfig.Transit transit =
                new AppConfig.Transit(
                        "https://example.org",
                        Duration.ofHours(1),
                        "Station",
                        stops,
                        List.of("8"),
                        headsigns,
                        directions);
        stops.add("two");
        destinations.add("Other");
        ids.add(1);
        headsigns.clear();
        assertEquals(List.of("one"), transit.stopIds());
        assertEquals(Map.of("8", List.of("Center")), transit.headsigns());
        assertEquals(Map.of("8", List.of(0)), transit.directionIds());
        assertThrows(
                UnsupportedOperationException.class,
                () -> transit.headsigns().get("8").add("Other"));
        assertThrows(UnsupportedOperationException.class, () -> transit.directionIds().clear());
    }

    @ParameterizedTest
    @MethodSource("invalidSettings")
    void rejectsInvalidSettingsWithActionableKey(String toml, String expected) throws IOException {
        IOException exception = assertThrows(IOException.class, () -> load(toml));
        assertTrue(exception.getMessage().contains(expected), exception.getMessage());
        assertTrue(exception.getMessage().contains("config.toml"), exception.getMessage());
    }

    static Stream<Arguments> invalidSettings() {
        return Stream.of(
                Arguments.of("[transit]\nurl = 'ftp://example.org'", "transit.url"),
                Arguments.of("[transit]\nurl = 'https:///missing-host'", "transit.url"),
                Arguments.of("[transit]\nurl = 'https://example.org/#fragment'", "transit.url"),
                Arguments.of(
                        "[transit]\nupdate_interval_minutes = 0",
                        "transit.update_interval_minutes"),
                Arguments.of(
                        "[transit]\nupdate_interval_minutes = 2147483648",
                        "transit.update_interval_minutes"),
                Arguments.of("[transit]\nstop_name = ' '", "transit.stop_name"),
                Arguments.of("[transit]\nstop_ids = [3]", "transit.stop_ids[0]"),
                Arguments.of("[transit]\nlines = '8'", "transit.lines"),
                Arguments.of("[transit.headsigns]\n'8' = ['']", "transit.headsigns.8[0]"),
                Arguments.of("[transit.direction_ids]\n'8' = [2]", "transit.direction_ids.8[0]"),
                Arguments.of("[transit.direction_ids]\n'8' = ['0']", "transit.direction_ids.8[0]"),
                Arguments.of("[weather]\nlatitude = nan", "weather.latitude"),
                Arguments.of("[weather]\nlatitude = 91", "weather.latitude"),
                Arguments.of("[weather]\nlongitude = -181", "weather.longitude"),
                Arguments.of("[weather]\nunits = 'kelvin'", "weather.units"),
                Arguments.of(
                        "[weather]\nrefresh_interval_minutes = -1",
                        "weather.refresh_interval_minutes"),
                Arguments.of("[display]\ntime_zone = 'Not/AZone'", "display.time_zone"),
                Arguments.of("[display]\ntime_format = 'HH:jj'", "display.time_format"),
                Arguments.of("[display]\ntime_format = 'pH'", "display.time_format"),
                Arguments.of("[display]\nfull_screen = 'yes'", "display.full_screen"),
                Arguments.of("[display]\ntext_scale = inf", "display.text_scale"),
                Arguments.of("[display]\ntext_scale = 0", "display.text_scale"),
                Arguments.of("[display]\ndeparture_limit = 0", "display.departure_limit"),
                Arguments.of("[display]\ndeparture_limit = 201", "display.departure_limit"),
                Arguments.of("[display]\ndeparture_limit = 1.5", "display.departure_limit"),
                Arguments.of("[display]\ndeparture_limt = 5", "display.departure_limt"),
                Arguments.of("weather = 'Krakow'", "weather"),
                Arguments.of("cache_directory = ''", "cache_directory"));
    }

    @Test
    void reportsTomlSyntaxAndMissingFileErrors() throws IOException {
        IOException malformed = assertThrows(IOException.class, () -> load("[transit\n"));
        assertTrue(malformed.getMessage().contains("config.toml"));
        assertThrows(
                IOException.class, () -> AppConfig.load(temporaryDirectory.resolve("absent.toml")));
    }
}
