package org.pischedule.config;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/** External settings. Relative cache paths are resolved against the configuration file. */
public record AppConfig(Transit transit, Weather weather, Display display, Path cacheDirectory) {
    public record Transit(String url, Duration updateInterval, String stopName,
                          List<String> stopIds, List<String> lines,
                          Map<String, List<String>> headsigns,
                          Map<String, List<Integer>> directionIds) {
        public Transit {
            stopIds = List.copyOf(stopIds);
            lines = List.copyOf(lines);
            headsigns = immutableLists(headsigns);
            directionIds = immutableLists(directionIds);
        }
    }

    public record Weather(double latitude, double longitude, String location, String units,
                          Duration refreshInterval) { }

    public record Display(ZoneId timeZone, String timeFormat, boolean fullScreen,
                          double textScale, int departureLimit) { }

    private static <T> Map<String, List<T>> immutableLists(Map<String, List<T>> input) {
        Map<String, List<T>> result = new LinkedHashMap<>();
        input.forEach((key, values) -> result.put(key, List.copyOf(values)));
        return Map.copyOf(result);
    }

    /** Load a TOML file. Missing keys use defaults; malformed or invalid settings cause an IOException. */
    public static AppConfig load(Path path) throws IOException {
        TomlParseResult root = Toml.parse(path);
        if (root.hasErrors()) {
            throw new IOException("Invalid configuration " + path + ": " + root.errors());
        }
        try {
            return parse(root, path);
        } catch (ConfigException e) {
            throw new IOException("Invalid configuration " + path + ": " + e.getMessage(), e);
        }
    }

    private static AppConfig parse(TomlTable root, Path path) throws ConfigException {
        checkKeys(root, "", Set.of("cache_directory", "transit", "weather", "display"));
        TomlTable transit = table(root, "transit", "transit");
        TomlTable weather = table(root, "weather", "weather");
        TomlTable display = table(root, "display", "display");
        checkKeys(transit, "transit.", Set.of("url", "update_interval_minutes", "stop_name",
                "stop_ids", "lines", "headsigns", "direction_ids"));
        checkKeys(weather, "weather.", Set.of("latitude", "longitude", "location", "units",
                "refresh_interval_minutes"));
        checkKeys(display, "display.", Set.of("time_zone", "time_format", "full_screen",
                "text_scale", "departure_limit"));

        String url = string(transit, "url", "transit.url", "https://gtfs.ztp.krakow.pl/GTFS_KRK_T.zip");
        try {
            URI uri = new URI(url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getFragment() != null) {
                throw new ConfigException("transit.url must be an absolute HTTP(S) URL without a fragment");
            }
        } catch (URISyntaxException e) {
            throw new ConfigException("transit.url must be an absolute HTTP(S) URL: " + e.getMessage());
        }
        Transit transitConfig = new Transit(url,
                minutes(transit, "update_interval_minutes", "transit.update_interval_minutes", 1440),
                string(transit, "stop_name", "transit.stop_name", "Łagiewniki"),
                strings(value(transit, "stop_ids"), "transit.stop_ids"),
                strings(value(transit, "lines"), "transit.lines"),
                stringFilters(table(transit, "headsigns", "transit.headsigns")),
                directionFilters(table(transit, "direction_ids", "transit.direction_ids")));

        double latitude = number(weather, "latitude", "weather.latitude", 50.0647);
        double longitude = number(weather, "longitude", "weather.longitude", 19.9450);
        if (latitude < -90 || latitude > 90) throw new ConfigException("weather.latitude must be between -90 and 90");
        if (longitude < -180 || longitude > 180) throw new ConfigException("weather.longitude must be between -180 and 180");
        String units = string(weather, "units", "weather.units", "celsius");
        if (!units.equals("celsius") && !units.equals("fahrenheit")) {
            throw new ConfigException("weather.units must be 'celsius' or 'fahrenheit'");
        }
        Weather weatherConfig = new Weather(latitude, longitude,
                string(weather, "location", "weather.location", "Kraków"), units,
                minutes(weather, "refresh_interval_minutes", "weather.refresh_interval_minutes", 15));

        String zone = string(display, "time_zone", "display.time_zone", "Europe/Warsaw");
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new ConfigException("display.time_zone must be a valid time zone, e.g. Europe/Warsaw");
        }
        String format = string(display, "time_format", "display.time_format", "HH:mm");
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
            ZonedDateTime sample = ZonedDateTime.of(2024, 12, 29, 0, 59, 59, 0, zoneId);
            for (int hour = 0; hour < 24; hour++) formatter.format(sample.withHour(hour));
            formatter.format(sample.withMonth(2));
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new ConfigException("display.time_format must be a valid Java time pattern: " + e.getMessage());
        }
        double textScale = number(display, "text_scale", "display.text_scale", 1.0);
        if (textScale <= 0 || textScale > 10) {
            throw new ConfigException("display.text_scale must be greater than 0 and at most 10");
        }
        long limit = integer(display, "departure_limit", "display.departure_limit", 6);
        if (limit < 1 || limit > 200) throw new ConfigException("display.departure_limit must be between 1 and 200");
        Display displayConfig = new Display(zoneId, format,
                bool(display, "full_screen", "display.full_screen", true), textScale, (int) limit);

        String cache = string(root, "cache_directory", "cache_directory", "cache");
        Path cachePath;
        try {
            cachePath = Path.of(cache);
            if (!cachePath.isAbsolute()) cachePath = path.toAbsolutePath().getParent().resolve(cachePath);
            cachePath = cachePath.normalize();
        } catch (InvalidPathException e) {
            throw new ConfigException("cache_directory is not a valid path: " + e.getMessage());
        }
        return new AppConfig(transitConfig, weatherConfig, displayConfig, cachePath);
    }

    private static void checkKeys(TomlTable table, String prefix, Set<String> allowed) throws ConfigException {
        if (table == null) return;
        for (String key : table.keySet()) {
            if (!allowed.contains(key)) throw new ConfigException("unknown setting " + prefix + key);
        }
    }

    private static Object value(TomlTable table, String key) {
        return table == null ? null : table.get(List.of(key));
    }

    private static TomlTable table(TomlTable parent, String key, String label) throws ConfigException {
        Object value = value(parent, key);
        if (value == null) return null;
        if (value instanceof TomlTable result) return result;
        throw new ConfigException(label + " must be a table");
    }

    private static String string(TomlTable table, String key, String label, String defaultValue) throws ConfigException {
        Object value = value(table, key);
        if (value == null) return defaultValue;
        if (!(value instanceof String result) || result.isBlank()) throw new ConfigException(label + " must be a non-empty string");
        return result;
    }

    private static long integer(TomlTable table, String key, String label, long defaultValue) throws ConfigException {
        Object value = value(table, key);
        if (value == null) return defaultValue;
        if (value instanceof Long result) return result;
        throw new ConfigException(label + " must be an integer");
    }

    private static double number(TomlTable table, String key, String label, double defaultValue) throws ConfigException {
        Object value = value(table, key);
        if (value == null) return defaultValue;
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) return number.doubleValue();
        throw new ConfigException(label + " must be a finite number");
    }

    private static boolean bool(TomlTable table, String key, String label, boolean defaultValue) throws ConfigException {
        Object value = value(table, key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean result) return result;
        throw new ConfigException(label + " must be a boolean");
    }

    private static Duration minutes(TomlTable table, String key, String label, long defaultValue) throws ConfigException {
        long value = integer(table, key, label, defaultValue);
        // Bound the interval so that scheduling conversions to milliseconds remain safe.
        if (value < 1 || value > Integer.MAX_VALUE) throw new ConfigException(label + " must be between 1 and " + Integer.MAX_VALUE + " minutes");
        return Duration.ofMinutes(value);
    }

    private static List<String> strings(Object value, String label) throws ConfigException {
        if (value == null) return List.of();
        if (!(value instanceof TomlArray array)) throw new ConfigException(label + " must be an array of non-empty strings");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Object item = array.get(i);
            if (!(item instanceof String text) || text.isBlank()) {
                throw new ConfigException(label + "[" + i + "] must be a non-empty string");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static Map<String, List<String>> stringFilters(TomlTable table) throws ConfigException {
        if (table == null) return Map.of();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            String line = entry.getKey();
            if (line.isBlank()) throw new ConfigException("transit.headsigns line name must not be empty");
            result.put(line, strings(entry.getValue(), "transit.headsigns." + line));
        }
        return result;
    }

    private static Map<String, List<Integer>> directionFilters(TomlTable table) throws ConfigException {
        if (table == null) return Map.of();
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            String line = entry.getKey();
            String label = "transit.direction_ids." + line;
            if (line.isBlank()) throw new ConfigException("transit.direction_ids line name must not be empty");
            if (!(entry.getValue() instanceof TomlArray array)) throw new ConfigException(label + " must be an array of 0 or 1");
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                Object item = array.get(i);
                if (!(item instanceof Long id) || (id != 0 && id != 1)) {
                    throw new ConfigException(label + "[" + i + "] must be 0 or 1");
                }
                ids.add(id.intValue());
            }
            result.put(line, List.copyOf(ids));
        }
        return result;
    }

    private static final class ConfigException extends Exception {
        private ConfigException(String message) { super(message); }
    }
}
