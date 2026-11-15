package org.pischedule;

import org.pischedule.config.AppConfig;
import org.pischedule.transit.TransitService;
import org.pischedule.transit.TransitSnapshot;
import org.pischedule.ui.ApplicationShell;
import org.pischedule.weather.WeatherService;
import org.pischedule.weather.WeatherSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.*;

public class DashboardApp {
    private static final Logger LOGGER = Logger.getLogger(DashboardApp.class.getName());
    private static final int DISPLAY_REFRESH_TIME = 30;
    private static final WeatherSnapshot WEATHER_UNAVAILABLE_SNAPSHOT =
            new WeatherSnapshot("", "", "", "Weather unavailable");
    private final AppConfig config;
    private final TransitService transit;
    private final WeatherService weather;
    private final AtomicBoolean stopped;
    private final AtomicReference<String> transitError;
    private final AtomicReference<String> weatherError;

    public DashboardApp(AppConfig config, TransitService transit, WeatherService weather) {
        this.config = config;
        this.transit = transit;
        this.weather = weather;
        this.stopped = new AtomicBoolean();
        this.transitError = new AtomicReference<>("");
        this.weatherError = new AtomicReference<>("");
    }

    public void start() {
        // Fixed-delay jobs bound the queue and ensure that a slow update never overlaps itself.
        // Separate snapshot workers keep weather responsive even while a timetable query waits.
        ScheduledExecutorService workers = buildWorkers();
        Runnable stop = setUpShutdownJob(workers);
        ApplicationShell shell = new ApplicationShell(config, stop);
        // Buffer extra groups so the view can replace expired rows between queries.
        int snapshotLimit =
                Math.min(TransitService.MAX_RESULTS, config.display().departureLimit() + 10);

        workers.scheduleWithFixedDelay(
                this::updateTimetable,
                0,
                config.transit().updateInterval().toMillis(),
                TimeUnit.MILLISECONDS);

        workers.scheduleWithFixedDelay(
                this::updateWeather,
                0,
                config.weather().refreshInterval().toMillis(),
                TimeUnit.MILLISECONDS);

        workers.scheduleWithFixedDelay(
                () -> displayTransitSnapshot(snapshotLimit, shell),
                0,
                DISPLAY_REFRESH_TIME,
                TimeUnit.SECONDS);

        workers.scheduleWithFixedDelay(
                () -> displayWeatherSnapshot(shell), 0, DISPLAY_REFRESH_TIME, TimeUnit.SECONDS);
    }

    private void displayWeatherSnapshot(ApplicationShell shell) {
        try {
            WeatherSnapshot result = weather.snapshot(Instant.now());
            WeatherSnapshot displayed =
                    new WeatherSnapshot(
                            result.temperature(),
                            result.condition(),
                            result.icon(),
                            appendStatus(result.status(), weatherError.get()));
            SwingUtilities.invokeLater(() -> {
                if (!stopped.get()) {
                    shell.dashboard().setWeather(displayed);
                }
            });
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Weather snapshot failed: " + e.getMessage(), e);
            SwingUtilities.invokeLater(() -> {
                if (!stopped.get()) {
                    shell.dashboard().setWeather(WEATHER_UNAVAILABLE_SNAPSHOT);
                }
            });
        }
    }

    private void displayTransitSnapshot(int snapshotLimit, ApplicationShell shell) {
        try {
            TransitSnapshot result = transit.snapshotGroups(Instant.now(), snapshotLimit);
            String status = appendStatus(result.status(), transitError.get());
            TransitSnapshot displayed =
                    new TransitSnapshot(result.departures(), result.stopName(), status);
            SwingUtilities.invokeLater(() -> {
                if (!stopped.get()) {
                    shell.dashboard().setTransit(displayed);
                }
            });
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Timetable query failed: " + e.getMessage(), e);
            TransitSnapshot unavailable =
                    new TransitSnapshot(
                            List.of(), config.transit().stopName(), "Timetable unavailable");
            SwingUtilities.invokeLater(() -> {
                if (!stopped.get()) {
                    shell.dashboard().setTransit(unavailable);
                }
            });
        }
    }

    private void updateWeather() {
        try {
            weather.update();
            weatherError.set("");
        } catch (Exception e) {
            if (!stopped.get()) {
                weatherError.set("Weather update failed");
                LOGGER.log(Level.WARNING, "Weather update failed: " + e.getMessage(), e);
            }
        }
    }

    private void updateTimetable() {
        try {
            transit.update();
            transitError.set("");
        } catch (Exception e) {
            if (!stopped.get()) {
                transitError.set("Timetable update failed");
                LOGGER.log(Level.WARNING, "Timetable update failed: " + e.getMessage(), e);
            }
        }
    }

    private static ScheduledExecutorService buildWorkers() {
        return Executors.newScheduledThreadPool(
                4,
                task -> {
                    Thread thread = new Thread(task, "pischedule-worker");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private Runnable setUpShutdownJob(ScheduledExecutorService workers) {
        Runnable stop =
                () -> {
                    if (stopped.compareAndSet(false, true)) {
                        workers.shutdownNow();
                    }
                };
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    stop.run();
                                    try {
                                        workers.awaitTermination(5, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                },
                                "pischedule-shutdown"));
        return stop;
    }

    private static String appendStatus(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        if (second == null || second.isBlank() || first.equalsIgnoreCase(second)) {
            return first;
        }
        return first + " · " + second;
    }
}
