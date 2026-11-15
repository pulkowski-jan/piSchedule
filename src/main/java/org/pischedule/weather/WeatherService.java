package org.pischedule.weather;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pischedule.config.AppConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Open-Meteo weather retrieval and last-success cache. Call update off the UI thread;
 * snapshot never performs I/O. The owner is responsible for scheduling refreshes.
 */
public final class WeatherService {
    private static final int MAX_BYTES = 64 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MINIMUM_MAX_AGE = Duration.ofMinutes(30);
    private static final String CACHE_FILE = "weather.json";
    private static final ObjectMapper JSON =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(32)
                                                    .maxStringLength(8192)
                                                    .maxNumberLength(64)
                                                    .build())
                                    .build())
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final WeatherSnapshot WEATHER_UNAVAILABLE_SNAPSHOT =
            new WeatherSnapshot("", "", "", "Weather unavailable");
    private static final Condition UNKNOWN_CONDITION = new Condition("", "");

    private final AppConfig.Weather config;
    private final Path cacheDirectory;
    private final Unit unit;
    private final Duration maxAge;
    private final HttpTransport transport;
    private final Clock clock;
    private final AtomicReference<State> state;

    public WeatherService(AppConfig.Weather config, Path cacheDirectory) {
        this(config, cacheDirectory, new JdkHttpTransport(), Clock.systemUTC());
    }

    // Package-private seams allow deterministic, network-free tests.
    WeatherService(
            AppConfig.Weather config, Path cacheDirectory, HttpTransport transport, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.unit = Unit.parse(config.units());
        Duration interval = Objects.requireNonNull(config.refreshInterval(), "refreshInterval");
        validate(config, interval);
        Duration twiceInterval = getTwiceOrMaxValue(interval);
        this.maxAge =
                twiceInterval.compareTo(MINIMUM_MAX_AGE) < 0 ? MINIMUM_MAX_AGE : twiceInterval;
        // A cache has not yet been verified in this process, so expose it as stale
        // until a refresh succeeds, even when it is very recent.
        this.state = new AtomicReference<>(new State(loadCache(), true));
    }

    /**
     * Returns immediately and never starts a network request.
     */
    public WeatherSnapshot snapshot(Instant now) {
        Objects.requireNonNull(now, "now");
        State current = state.get();
        if (current.reading == null) {
            return WEATHER_UNAVAILABLE_SNAPSHOT;
        }
        Reading reading = current.reading;
        Condition condition = conditionFor(reading.code, reading.day);
        boolean stale =
                current.failed
                        || tooOld(reading.fetchedAt, now)
                        || tooOld(reading.observedAt, now)
                        || tooFarInFuture(reading.fetchedAt, now)
                        || tooFarInFuture(reading.observedAt, now)
                        || UNKNOWN_CONDITION.equals(condition);
        return new WeatherSnapshot(
                Math.round(reading.temperature) + unit.suffix,
                condition.description,
                condition.icon,
                stale ? "Weather stale" : "");
    }

    /**
     * Refreshes synchronously. Concurrent calls are serialized, while snapshots remain
     * non-blocking. Failures preserve the last reading and mark it stale.
     */
    public synchronized void update() throws Exception {
        try {
            HttpRequest request = buildWeatherRequest();
            Response response = transport.send(request);
            validateWeatherResponse(response);
            Instant fetchedAt = clock.instant();
            Reading reading = parseProviderResponse(response.body, fetchedAt);
            Reading previous = state.get().reading;
            if (previous != null && reading.observedAt.isBefore(previous.observedAt)) {
                throw new IOException("Weather response is older than the cached observation");
            }
            updateCache(reading);
            state.set(new State(reading, false));
        } catch (Exception ex) {
            state.updateAndGet(previous -> new State(previous.reading, true));
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw ex;
        }
    }

    private void updateCache(Reading reading) throws IOException {
        try {
            writeCache(reading);
        } catch (IOException | RuntimeException ex) {
            // The new reading is usable, but persistence failed. Do not silently
            // present the whole refresh as healthy.
            state.set(new State(reading, true));
            throw ex;
        }
    }

    private static void validateWeatherResponse(Response response) throws IOException {
        if (response == null
                || response.status != 200
                || response.body == null
                || response.body.length > MAX_BYTES) {
            throw new IOException("Invalid weather HTTP response");
        }
    }

    private HttpRequest buildWeatherRequest() {
        return HttpRequest.newBuilder(requestUri())
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private URI requestUri() {
        return URI.create(
                "https://api.open-meteo.com/v1/forecast?latitude="
                        + Double.toString(config.latitude())
                        + "&longitude="
                        + Double.toString(config.longitude())
                        + "&current=temperature_2m,weather_code,is_day&temperature_unit="
                        + unit.apiName
                        + "&timeformat=unixtime&timezone=GMT");
    }

    private Reading parseProviderResponse(byte[] bytes, Instant fetchedAt) throws IOException {
        JsonNode root = parseJson(bytes);
        JsonNode current = requiredObject(root, "current");
        JsonNode units = requiredObject(root, "current_units");
        validateUnits(units);
        double temperature = requiredNumber(current, "temperature_2m");
        int code = requiredInt(current, "weather_code");
        int day = requiredInt(current, "is_day");
        Instant observedAt = epochSeconds(current, "time");
        Reading reading = validateReading(temperature, code, day, observedAt, fetchedAt);
        if (tooFarInFuture(observedAt, fetchedAt)) {
            throw new IOException("Weather observation is in the future");
        }
        return reading;
    }

    private void validateUnits(JsonNode units) throws IOException {
        if (!unit.providerSymbol.equals(requiredText(units, "temperature_2m"))) {
            throw new IOException("Unexpected weather temperature unit");
        }
        if (!"unixtime".equals(requiredText(units, "time"))) {
            throw new IOException("Unexpected weather time unit");
        }
    }

    private Reading loadCache() {
        try {
            Path path = cacheDirectory.resolve(CACHE_FILE);
            // Do not open devices, named pipes, or symlinks as cache files.
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(path)) {
                bytes = in.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) {
                return null;
            }
            JsonNode root = parseJson(bytes);
            if (requiredInt(root, "version") != 1
                    || Double.compare(requiredNumber(root, "latitude"), config.latitude()) != 0
                    || Double.compare(requiredNumber(root, "longitude"), config.longitude()) != 0
                    || !unit.apiName.equals(requiredText(root, "units"))) {
                return null;
            }
            Reading reading =
                    validateReading(
                            requiredNumber(root, "temperature"),
                            requiredInt(root, "code"),
                            requiredInt(root, "is_day"),
                            epochSeconds(root, "observed_at"),
                            epochSeconds(root, "fetched_at"));
            if (tooFarInFuture(reading.observedAt, reading.fetchedAt)) {
                return null;
            }
            return reading;
        } catch (IOException | RuntimeException ex) {
            // A missing, incompatible, or corrupt cache is equivalent to no cache.
            return null;
        }
    }

    private void writeCache(Reading reading) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", 1);
        root.put("latitude", config.latitude());
        root.put("longitude", config.longitude());
        root.put("units", unit.apiName);
        root.put("temperature", reading.temperature);
        root.put("code", reading.code);
        root.put("is_day", reading.day ? 1 : 0);
        root.put("observed_at", reading.observedAt.getEpochSecond());
        root.put("fetched_at", reading.fetchedAt.getEpochSecond());
        byte[] data = JSON.writeValueAsBytes(root);
        Files.createDirectories(cacheDirectory);
        Path temporary = Files.createTempFile(cacheDirectory, ".weather-", ".tmp");
        try {
            Files.write(temporary, data);
            // Never fall back to a non-atomic replacement: a failed refresh must not
            // leave an existing last-success cache partially replaced.
            Files.move(
                    temporary,
                    cacheDirectory.resolve(CACHE_FILE),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Reading validateReading(
            double temperature, int code, int day, Instant observedAt, Instant fetchedAt)
            throws IOException {
        // Reject non-finite and physically implausible temperatures rather than show garbage.
        double celsius = unit == Unit.CELSIUS ? temperature : (temperature - 32) * 5 / 9;
        if (!Double.isFinite(temperature)
                || celsius < -120
                || celsius > 80
                || (day != 0 && day != 1)) {
            throw new IOException("Invalid weather values");
        }
        if (UNKNOWN_CONDITION.equals(conditionFor(code, day == 1))) {
            throw new IOException("Unknown WMO weather code: " + code);
        }
        return new Reading(temperature, code, day == 1, observedAt, fetchedAt);
    }

    private static Duration getTwiceOrMaxValue(Duration interval) {
        Duration twiceInterval;
        try {
            twiceInterval = interval.multipliedBy(2);
        } catch (ArithmeticException ex) {
            twiceInterval = Duration.ofSeconds(Long.MAX_VALUE);
        }
        return twiceInterval;
    }

    private static void validate(AppConfig.Weather config, Duration interval) {
        if (areCoordinatesInvalid(config)) {
            throw new IllegalArgumentException("Invalid weather coordinates");
        }
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Weather refresh interval must be positive");
        }
    }

    private static boolean areCoordinatesInvalid(AppConfig.Weather config) {
        return !Double.isFinite(config.latitude())
                || config.latitude() < -90
                || config.latitude() > 90
                || !Double.isFinite(config.longitude())
                || config.longitude() < -180
                || config.longitude() > 180;
    }

    private boolean tooOld(Instant time, Instant now) {
        return time.isBefore(now) && Duration.between(time, now).compareTo(maxAge) > 0;
    }

    private static boolean tooFarInFuture(Instant time, Instant now) {
        return time.isAfter(now) && Duration.between(now, time).compareTo(CLOCK_SKEW) > 0;
    }

    private static JsonNode parseJson(byte[] bytes) throws IOException {
        JsonNode node = JSON.readTree(bytes);
        if (node == null || !node.isObject()) {
            throw new IOException("Expected a weather JSON object");
        }
        return node;
    }

    private static JsonNode requiredObject(JsonNode parent, String name) throws IOException {
        JsonNode node = parent.get(name);
        if (node == null || !node.isObject()) {
            throw new IOException("Missing weather object: " + name);
        }
        return node;
    }

    private static String requiredText(JsonNode parent, String name) throws IOException {
        JsonNode node = parent.get(name);
        if (node == null || !node.isTextual()) {
            throw new IOException("Missing weather text: " + name);
        }
        return node.textValue();
    }

    private static double requiredNumber(JsonNode parent, String name) throws IOException {
        JsonNode node = parent.get(name);
        if (node == null || !node.isNumber() || !Double.isFinite(node.doubleValue())) {
            throw new IOException("Invalid weather number: " + name);
        }
        return node.doubleValue();
    }

    private static int requiredInt(JsonNode parent, String name) throws IOException {
        JsonNode node = parent.get(name);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new IOException("Invalid weather integer: " + name);
        }
        return node.intValue();
    }

    private static Instant epochSeconds(JsonNode parent, String name) throws IOException {
        JsonNode node = parent.get(name);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new IOException("Invalid weather timestamp: " + name);
        }
        try {
            return Instant.ofEpochSecond(node.longValue());
        } catch (RuntimeException ex) {
            throw new IOException("Invalid weather timestamp: " + name, ex);
        }
    }

    // WMO interpretation codes supported by Open-Meteo's current weather API.
    private static Condition conditionFor(int code, boolean day) {
        return switch (code) {
            case 0, 1 -> new Condition("", day ? "☀" : "☾");
            case 2 -> new Condition("", day ? "⛅" : "☁");
            case 3 -> new Condition("", "☁");
            case 45, 48 -> new Condition("Mgła", "☁");
            case 51, 53, 55, 56, 57 -> new Condition("Mżawka", "☂");
            case 61 -> new Condition("Słaby deszcz", "☂");
            case 63 -> new Condition("Deszcz", "☂");
            case 65 -> new Condition("Silny deszcz", "☂");
            case 66, 67 -> new Condition("Marznący deszcz", "☂");
            case 71 -> new Condition("Słaby śnieg", "❄");
            case 73, 77 -> new Condition("Śnieg", "❄");
            case 75 -> new Condition("Silny śnieg", "❄");
            case 80, 81, 82 -> new Condition("Przelotny deszcz", "☂");
            case 85, 86 -> new Condition("Przelotny śnieg", "❄");
            case 95 -> new Condition("Burza", "⚡");
            case 96, 99 -> new Condition("Burza z gradem", "⚡");
            default -> UNKNOWN_CONDITION;
        };
    }

    private enum Unit {
        CELSIUS("celsius", "°C", "°C"),
        FAHRENHEIT("fahrenheit", "°F", "°F");

        final String apiName;
        final String providerSymbol;
        final String suffix;

        Unit(String apiName, String providerSymbol, String suffix) {
            this.apiName = apiName;
            this.providerSymbol = providerSymbol;
            this.suffix = suffix;
        }

        static Unit parse(String value) {
            if (value == null) {
                throw new IllegalArgumentException("Weather units are required");
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "metric", "celsius" -> CELSIUS;
                case "imperial", "fahrenheit" -> FAHRENHEIT;
                default ->
                        throw new IllegalArgumentException(
                                "Weather units must be metric/celsius or imperial/fahrenheit");
            };
        }
    }

    private record Reading(
            double temperature, int code, boolean day, Instant observedAt, Instant fetchedAt) {}

    private record State(Reading reading, boolean failed) {}

    private record Condition(String description, String icon) {}

    @FunctionalInterface
    interface HttpTransport {
        Response send(HttpRequest request) throws Exception;
    }

    record Response(int status, byte[] body) {}

    private static final class JdkHttpTransport implements HttpTransport {
        private final HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();

        @Override
        public Response send(HttpRequest request) throws Exception {
            CompletableFuture<HttpResponse<byte[]>> future =
                    client.sendAsync(request, ignored -> new LimitedBodySubscriber(MAX_BYTES));
            try {
                HttpResponse<byte[]> response =
                        future.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                return new Response(response.statusCode(), response.body());
            } catch (TimeoutException | InterruptedException ex) {
                future.cancel(true);
                throw ex;
            }
        }
    }

    /**
     * The size limit applies while receiving, not after an unbounded allocation.
     */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBodySubscriber(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int length = buffer.remaining();
                if (length > limit - output.size()) {
                    subscription.cancel();
                    result.completeExceptionally(
                            new IOException("Weather response exceeds size limit"));
                    return;
                }
                byte[] chunk = new byte[length];
                buffer.get(chunk);
                output.write(chunk, 0, chunk.length);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(output.toByteArray());
        }
    }
}
