package org.pischedule.weather;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pischedule.config.AppConfig;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WeatherServiceTest {
    private static final Instant NOW = Instant.parse("2025-01-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path directory;

    private static AppConfig.Weather config(String units) {
        return new AppConfig.Weather(50.0614, 19.9366, "Kraków", units, Duration.ofMinutes(15));
    }

    private static byte[] response(double temperature, int code, int day, Instant observed, String unit) {
        String json = "{\"current_units\":{\"time\":\"unixtime\",\"temperature_2m\":\"" + unit
                + "\"},\"current\":{\"time\":" + observed.getEpochSecond() + ",\"temperature_2m\":"
                + temperature + ",\"weather_code\":" + code + ",\"is_day\":" + day + "}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static WeatherService.Response ok(byte[] body) {
        return new WeatherService.Response(200, body);
    }

    @Test
    void refreshBuildsRequestAndSnapshotIsDisplayReadyWithoutNetwork() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        WeatherService service = new WeatherService(config("metric"), directory, request -> {
            requests.incrementAndGet();
            sent.set(request);
            return ok(response(12.6, 2, 1, NOW, "°C"));
        }, CLOCK);
        assertEquals("Weather unavailable", service.snapshot(NOW).status());
        assertEquals(0, requests.get());
        service.update();
        WeatherSnapshot result = service.snapshot(NOW);
        assertEquals("13°C", result.temperature());
        assertEquals("", result.condition());
        assertFalse(result.icon().isBlank());
        assertEquals("", result.status());
        assertTrue(sent.get().uri().toString().startsWith("https://api.open-meteo.com/v1/forecast?"));
        assertTrue(sent.get().uri().getQuery().contains("latitude=50.0614"));
        assertTrue(sent.get().uri().getQuery().contains("longitude=19.9366"));
        assertTrue(sent.get().uri().getQuery().contains("temperature_unit=celsius"));
        assertTrue(sent.get().timeout().isPresent());
        for (int i = 0; i < 10; i++) service.snapshot(NOW);
        assertEquals(1, requests.get());
    }

    @Test
    void fahrenheitAndDayNightIcon() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>(response(50.4, 0, 0, NOW, "°F"));
        WeatherService service = new WeatherService(config("imperial"), directory,
                request -> {
                    assertTrue(request.uri().getQuery().contains("temperature_unit=fahrenheit"));
                    return ok(body.get());
                }, CLOCK);
        service.update();
        assertEquals("50°F", service.snapshot(NOW).temperature());
        assertEquals("☾", service.snapshot(NOW).icon());
        body.set(response(50.4, 0, 1, NOW, "°F"));
        service.update();
        assertEquals("☀", service.snapshot(NOW).icon());
    }

    @Test
    void ageAndFailedRefreshMarkLastSuccessStaleAndLaterSuccessClearsIt() throws Exception {
        AtomicReference<WeatherService.Response> next = new AtomicReference<>(ok(response(4, 61, 1, NOW, "°C")));
        WeatherService service = new WeatherService(config("celsius"), directory, request -> next.get(), CLOCK);
        service.update();
        assertEquals("", service.snapshot(NOW.plus(Duration.ofMinutes(30))).status());
        assertEquals("Weather stale", service.snapshot(NOW.plus(Duration.ofMinutes(30)).plusSeconds(1)).status());
        assertEquals("Weather stale", service.snapshot(NOW.minus(Duration.ofMinutes(6))).status());
        next.set(new WeatherService.Response(503, new byte[0]));
        assertThrows(IOException.class, service::update);
        assertEquals("Weather stale", service.snapshot(NOW).status());
        assertEquals("4°C", service.snapshot(NOW).temperature());
        next.set(ok(response(5, 63, 1, NOW, "°C")));
        service.update();
        assertEquals("", service.snapshot(NOW).status());
        assertEquals("5°C", service.snapshot(NOW).temperature());
    }

    @Test
    void oldObservationsAreStaleEvenWhenJustFetched() throws Exception {
        WeatherService service = new WeatherService(config("metric"), directory,
                request -> ok(response(2, 3, 1, NOW.minus(Duration.ofHours(1)), "°C")), CLOCK);
        service.update();
        assertEquals("Weather stale", service.snapshot(NOW).status());
    }

    @Test
    void restartLoadsLastSuccessWithoutNetworkAndFailedRefreshPreservesDiskCache() throws Exception {
        AtomicReference<WeatherService.Response> next = new AtomicReference<>(ok(response(-3, 71, 1, NOW, "°C")));
        WeatherService first = new WeatherService(config("metric"), directory, request -> next.get(), CLOCK);
        first.update();
        byte[] originalCache = Files.readAllBytes(directory.resolve("weather.json"));
        next.set(ok("{bad".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, first::update);
        assertArrayEquals(originalCache, Files.readAllBytes(directory.resolve("weather.json")));
        WeatherService restarted = new WeatherService(config("celsius"), directory,
                request -> { throw new IOException("offline"); }, CLOCK);
        assertEquals("-3°C", restarted.snapshot(NOW).temperature());
        assertEquals("Słaby śnieg", restarted.snapshot(NOW).condition());
        assertEquals("Weather stale", restarted.snapshot(NOW).status());
        assertThrows(IOException.class, restarted::update);
        assertEquals("-3°C", restarted.snapshot(NOW).temperature());
        assertArrayEquals(originalCache, Files.readAllBytes(directory.resolve("weather.json")));
    }

    @Test
    void olderObservationDoesNotReplaceNewerCachedWeather() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>(response(12, 0, 1, NOW, "°C"));
        WeatherService service = new WeatherService(config("celsius"), directory, request -> ok(body.get()), CLOCK);
        service.update();
        byte[] saved = Files.readAllBytes(directory.resolve("weather.json"));
        body.set(response(3, 61, 1, NOW.minusSeconds(3600), "°C"));
        assertThrows(IOException.class, service::update);
        assertEquals("12°C", service.snapshot(NOW).temperature());
        assertEquals("Weather stale", service.snapshot(NOW).status());
        assertArrayEquals(saved, Files.readAllBytes(directory.resolve("weather.json")));
    }

    @Test
    void cacheIsScopedToCoordinatesAndUnitsAndIgnoresCorruption() throws Exception {
        WeatherService first = new WeatherService(config("metric"), directory,
                request -> ok(response(12, 0, 1, NOW, "°C")), CLOCK);
        first.update();
        AppConfig.Weather elsewhere = new AppConfig.Weather(51, 19.9366, "Elsewhere", "metric", Duration.ofMinutes(15));
        assertEquals("Weather unavailable", new WeatherService(elsewhere, directory, request -> null, CLOCK).snapshot(NOW).status());
        assertEquals("Weather unavailable", new WeatherService(config("imperial"), directory, request -> null, CLOCK).snapshot(NOW).status());
        Files.writeString(directory.resolve("weather.json"), "{\"version\":1,\"version\":1}");
        assertEquals("Weather unavailable", new WeatherService(config("metric"), directory, request -> null, CLOCK).snapshot(NOW).status());
        Files.write(directory.resolve("weather.json"), new byte[65537]);
        assertEquals("Weather unavailable", new WeatherService(config("metric"), directory, request -> null, CLOCK).snapshot(NOW).status());
    }

    @Test
    void cacheReadAndWriteFailuresDoNotPreventConstructionOrLoseTheUsableReading() throws Exception {
        Path notADirectory = directory.resolve("not-a-directory");
        Files.writeString(notADirectory, "file");
        WeatherService service = assertDoesNotThrow(() -> new WeatherService(config("celsius"), notADirectory,
                request -> ok(response(7, 3, 1, NOW, "°C")), CLOCK));
        assertEquals("Weather unavailable", service.snapshot(NOW).status());
        assertThrows(IOException.class, service::update);
        assertEquals("7°C", service.snapshot(NOW).temperature());
        assertEquals("Weather stale", service.snapshot(NOW).status());
        assertEquals("file", Files.readString(notADirectory));
    }

    @Test
    void rejectsMalformedMissingOversizedAndImplausibleResponses() throws Exception {
        byte[] valid = response(4, 0, 1, NOW, "°C");
        String json = new String(valid, StandardCharsets.UTF_8);
        byte[][] invalid = {
                "not json".getBytes(StandardCharsets.UTF_8),
                "{}".getBytes(StandardCharsets.UTF_8),
                (json + " {}").getBytes(StandardCharsets.UTF_8),
                json.replace("\"weather_code\":0", "\"weather_code\":0,\"weather_code\":3").getBytes(StandardCharsets.UTF_8),
                json.replace("\"weather_code\":0", "\"weather_code\":999").getBytes(StandardCharsets.UTF_8),
                json.replace("\"weather_code\":0", "\"weather_code\":0.5").getBytes(StandardCharsets.UTF_8),
                json.replace("\"is_day\":1", "\"is_day\":2").getBytes(StandardCharsets.UTF_8),
                json.replace("\"temperature_2m\":4.0", "\"temperature_2m\":null").getBytes(StandardCharsets.UTF_8),
                json.replace("\"temperature_2m\":4.0", "\"temperature_2m\":999").getBytes(StandardCharsets.UTF_8),
                json.replace("°C", "°F").getBytes(StandardCharsets.UTF_8),
                json.replace("unixtime", "iso8601").getBytes(StandardCharsets.UTF_8),
                response(4, 0, 1, NOW.plus(Duration.ofMinutes(6)), "°C"),
                new byte[65537]
        };
        for (byte[] body : invalid) {
            WeatherService service = new WeatherService(config("metric"), directory, request -> ok(body), CLOCK);
            assertThrows(IOException.class, service::update, () -> "Accepted: " + new String(body, 0, Math.min(body.length, 200), StandardCharsets.UTF_8));
            assertEquals("Weather unavailable", service.snapshot(NOW).status());
        }
        assertFalse(Files.exists(directory.resolve("weather.json")));
    }

    @Test
    void streamingSubscriberCancelsAsSoonAsBodyExceedsLimit() {
        WeatherService.LimitedBodySubscriber subscriber = new WeatherService.LimitedBodySubscriber(4);
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            @Override public void request(long n) { }
            @Override public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[] {1, 2, 3})));
        assertFalse(cancelled.get());
        subscriber.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[] {4, 5})));
        assertTrue(cancelled.get());
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join());
    }

    @Test
    void mapsEveryOpenMeteoWmoCode() throws Exception {
        int[] codes = {0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67,
                71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99};
        String[] descriptions = {"", "", "", "", "Mgła", "Mgła",
                "Mżawka", "Mżawka", "Mżawka", "Mżawka", "Mżawka",
                "Słaby deszcz", "Deszcz", "Silny deszcz", "Marznący deszcz", "Marznący deszcz",
                "Słaby śnieg", "Śnieg", "Silny śnieg", "Śnieg", "Przelotny deszcz",
                "Przelotny deszcz", "Przelotny deszcz", "Przelotny śnieg", "Przelotny śnieg",
                "Burza", "Burza z gradem", "Burza z gradem"};
        assertEquals(codes.length, descriptions.length);
        AtomicInteger code = new AtomicInteger();
        WeatherService service = new WeatherService(config("metric"), directory,
                request -> ok(response(0, code.get(), 1, NOW, "°C")), CLOCK);
        for (int i = 0; i < codes.length; i++) {
            code.set(codes[i]);
            service.update();
            assertEquals(descriptions[i], service.snapshot(NOW).condition(), "WMO " + codes[i]);
            assertFalse(service.snapshot(NOW).icon().isBlank());
        }
    }

    @Test
    void snapshotDoesNotWaitForInFlightRefresh() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WeatherService service = new WeatherService(config("metric"), directory, request -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timed out");
            return ok(response(5, 0, 1, NOW, "°C"));
        }, CLOCK);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> refresh = executor.submit(() -> {
                try { service.update(); } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<WeatherSnapshot> snapshot = executor.submit(() -> service.snapshot(NOW));
            assertEquals("Weather unavailable", snapshot.get(1, TimeUnit.SECONDS).status());
            release.countDown();
            refresh.get(5, TimeUnit.SECONDS);
            assertEquals("", service.snapshot(NOW).status());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
