package org.pischedule.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals(Duration.ofMinutes(120), config.transit().lookAhead());
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
        assertNull(config.logFile());
        assertFalse(Files.exists(temporaryDirectory.resolve("logs")));
    }

    @Test
    void checkedInExampleMatchesDefaults() throws IOException {
        AppConfig example = AppConfig.load(Path.of("config.example.toml"));
        AppConfig defaults = load("");
        assertEquals(defaults.transit(), example.transit());
        assertEquals(defaults.weather(), example.weather());
        assertEquals(defaults.display(), example.display());
        assertNull(example.logFile());
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
                        log_file = "logs/../pischedule.log"
                        [transit]
                        url = "https://example.org/feed.zip"
                        update_interval_minutes = 30
                        look_ahead_minutes = 45
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
        assertEquals(Duration.ofMinutes(45), config.transit().lookAhead());
        assertEquals(temporaryDirectory.resolve("pischedule.log"), config.logFile());
        assertFalse(Files.exists(config.logFile()));
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
    void logPathIsResolvedAgainstConfigurationDirectory() throws IOException {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("nested"));
        Path path = directory.resolve("settings.toml");
        Files.writeString(path, "log_file = '../logs/pischedule.log'\n");
        assertEquals(
                temporaryDirectory.resolve("logs/pischedule.log"), AppConfig.load(path).logFile());
    }

    @Test
    void absoluteLogPathIsNotResolvedAgainstConfig() throws IOException {
        Path absolute = temporaryDirectory.resolve("absolute.log");
        AppConfig config = load("log_file = '" + absolute + "'\n");
        assertEquals(absolute, config.logFile());
        assertFalse(Files.exists(absolute));
    }

    @Test
    void acceptsLookAheadParsingBounds() throws IOException {
        assertEquals(
                Duration.ofMinutes(1),
                load("[transit]\nlook_ahead_minutes = 1").transit().lookAhead());
        assertEquals(
                Duration.ofDays(7),
                load("[transit]\nlook_ahead_minutes = 10080").transit().lookAhead());
    }

    @Test
    void legacyAppConfigConstructorDefaultsToStdout() throws IOException {
        AppConfig defaults = load("");
        AppConfig config =
                new AppConfig(
                        defaults.transit(),
                        defaults.weather(),
                        defaults.display(),
                        defaults.cacheDirectory());
        assertNull(config.logFile());
    }

    @Test
    void transitConstructorValidatesLookAhead() {
        for (Duration invalid :
                List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofDays(7).plusNanos(1))) {
            assertThrows(IllegalArgumentException.class, () -> transitWithLookAhead(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> transitWithLookAhead(null));
        assertEquals(Duration.ofNanos(1), transitWithLookAhead(Duration.ofNanos(1)).lookAhead());
        assertEquals(Duration.ofDays(7), transitWithLookAhead(Duration.ofDays(7)).lookAhead());
    }

    private AppConfig.Transit transitWithLookAhead(Duration lookAhead) {
        return new AppConfig.Transit(
                "https://example.org",
                Duration.ofHours(1),
                "Station",
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                lookAhead);
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
        assertEquals(Duration.ofMinutes(120), transit.lookAhead());
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
                Arguments.of("[transit]\nlook_ahead_minutes = 0", "transit.look_ahead_minutes"),
                Arguments.of("[transit]\nlook_ahead_minutes = -1", "transit.look_ahead_minutes"),
                Arguments.of("[transit]\nlook_ahead_minutes = 10081", "transit.look_ahead_minutes"),
                Arguments.of(
                        "[transit]\nlook_ahead_minutes = 9223372036854775807",
                        "transit.look_ahead_minutes"),
                Arguments.of("[transit]\nlook_ahead_minutes = 1.5", "transit.look_ahead_minutes"),
                Arguments.of("[transit]\nlook_ahead_minutes = 120.0", "transit.look_ahead_minutes"),
                Arguments.of("[transit]\nlook_ahead_minutes = '120'", "transit.look_ahead_minutes"),
                Arguments.of("[transit]\nlook_ahead_minutes = true", "transit.look_ahead_minutes"),
                Arguments.of("[transit]\nlook_ahead_minutes = []", "transit.look_ahead_minutes"),
                Arguments.of("[transit]\nlook_ahead_minutes = {}", "transit.look_ahead_minutes"),
                Arguments.of("[transit]\nlook_ahed_minutes = 120", "transit.look_ahed_minutes"),
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
                Arguments.of("cache_directory = ''", "cache_directory"),
                Arguments.of("log_file = ''", "log_file"),
                Arguments.of("log_file = '   '", "log_file"),
                Arguments.of("log_file = 120", "log_file"),
                Arguments.of("log_file = true", "log_file"),
                Arguments.of("log_file = []", "log_file"),
                Arguments.of("log_file = {}", "log_file"),
                Arguments.of("log_file = \"logs/\\u0000bad.log\"", "log_file"),
                Arguments.of("log_flie = 'app.log'", "log_flie"),
                Arguments.of("look_ahead_minutes = 120", "look_ahead_minutes"),
                Arguments.of("[transit]\nlog_file = 'app.log'", "transit.log_file"));
    }

    @Test
    void reportsTomlSyntaxAndMissingFileErrors() throws IOException {
        IOException malformed = assertThrows(IOException.class, () -> load("[transit\n"));
        assertTrue(malformed.getMessage().contains("config.toml"));
        assertThrows(
                IOException.class, () -> AppConfig.load(temporaryDirectory.resolve("absent.toml")));
    }
}
