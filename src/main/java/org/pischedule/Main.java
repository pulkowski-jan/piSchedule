package org.pischedule;

import org.pischedule.config.AppConfig;
import org.pischedule.transit.Stop;
import org.pischedule.transit.TransitService;
import org.pischedule.transit.TransitSnapshot;
import org.pischedule.ui.ApplicationShell;
import org.pischedule.weather.WeatherService;
import org.pischedule.weather.WeatherSnapshot;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        try {
            Arguments options = Arguments.parse(args);
            if (options.help) {
                System.out.println("Usage: java -jar piSchedule.jar [--config FILE] [--search-stops TEXT | --check-feed]");
                return;
            }
            AppConfig config = AppConfig.load(options.config);
            TransitService transit = new TransitService(config.transit(), config.cacheDirectory());
            if (options.search != null) {
                for (Stop stop : transit.searchStops(options.search)) {
                    System.out.printf("%s\t%s\tparent=%s\tplatform=%s%n", stop.id(), stop.name(),
                            stop.parentId() == null ? "" : stop.parentId(), stop.platform() == null ? "" : stop.platform());
                }
                return;
            }
            if (options.checkFeed) {
                transit.update();
                TransitSnapshot result = transit.snapshot(Instant.now(), config.display().departureLimit());
                System.out.println("Stop: " + result.stopName());
                System.out.println("Status: " + (result.status().isBlank() ? "ok" : result.status()));
                for (var departure : result.departures()) {
                    System.out.printf("%s\t%s\t%s\t%s\t%s\t%d%n", departure.time().atZone(config.display().timeZone()),
                            departure.line(), departure.destination(), departure.stopName(), departure.platform(),
                            departure.direction() != null ? departure.direction() : -1);
                }
                return;
            }
            if (GraphicsEnvironment.isHeadless()) {
                throw new IllegalStateException("A graphical environment with AWT/Swing support is required");
            }
            WeatherService weather = new WeatherService(config.weather(), config.cacheDirectory());
            SwingUtilities.invokeLater(() -> startDashboard(config, transit, weather));
        } catch (Exception e) {
            System.err.println("piSchedule: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void startDashboard(AppConfig config, TransitService transit, WeatherService weather) {
        // Fixed-delay jobs bound the queue and ensure that a slow update never overlaps itself.
        // Separate snapshot workers keep weather responsive even while a timetable query waits.
        ScheduledExecutorService workers = Executors.newScheduledThreadPool(4, task -> {
            Thread thread = new Thread(task, "pischedule-worker");
            thread.setDaemon(true);
            return thread;
        });
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicReference<String> transitError = new AtomicReference<>("");
        AtomicReference<String> weatherError = new AtomicReference<>("");
        Runnable stop = () -> {
            if (stopped.compareAndSet(false, true)) workers.shutdownNow();
        };
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop.run();
            try {
                workers.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pischedule-shutdown"));
        ApplicationShell shell = new ApplicationShell(config, stop);
        // Buffer extra groups so the view can replace expired rows between queries.
        int snapshotLimit = Math.min(TransitService.MAX_RESULTS, config.display().departureLimit() + 10);

        workers.scheduleWithFixedDelay(() -> {
            try {
                transit.update();
                transitError.set("");
            } catch (Exception e) {
                if (!stopped.get()) {
                    transitError.set("Timetable update failed");
                    System.err.println("Timetable update failed: " + e.getMessage());
                }
            }
        }, 0, config.transit().updateInterval().toMillis(), TimeUnit.MILLISECONDS);

        workers.scheduleWithFixedDelay(() -> {
            try {
                weather.update();
                weatherError.set("");
            } catch (Exception e) {
                if (!stopped.get()) {
                    weatherError.set("Weather update failed");
                    System.err.println("Weather update failed: " + e.getMessage());
                }
            }
        }, 0, config.weather().refreshInterval().toMillis(), TimeUnit.MILLISECONDS);

        workers.scheduleWithFixedDelay(() -> {
            try {
                TransitSnapshot result = transit.snapshotGroups(Instant.now(), snapshotLimit);
                String status = appendStatus(result.status(), transitError.get());
                TransitSnapshot displayed = new TransitSnapshot(result.departures(), result.stopName(), status);
                if (!stopped.get()) SwingUtilities.invokeLater(() -> {
                    if (!stopped.get()) shell.dashboard().setTransit(displayed);
                });
            } catch (Exception e) {
                System.err.println("Timetable query failed: " + e.getMessage());
                TransitSnapshot unavailable = new TransitSnapshot(List.of(), config.transit().stopName(), "Timetable unavailable");
                if (!stopped.get()) SwingUtilities.invokeLater(() -> {
                    if (!stopped.get()) shell.dashboard().setTransit(unavailable);
                });
            }
        }, 0, 10, TimeUnit.SECONDS);

        workers.scheduleWithFixedDelay(() -> {
            try {
                WeatherSnapshot result = weather.snapshot(Instant.now());
                WeatherSnapshot displayed = new WeatherSnapshot(result.temperature(), result.condition(), result.icon(),
                        appendStatus(result.status(), weatherError.get()));
                if (!stopped.get()) SwingUtilities.invokeLater(() -> {
                    if (!stopped.get()) shell.dashboard().setWeather(displayed);
                });
            } catch (Exception e) {
                System.err.println("Weather snapshot failed: " + e.getMessage());
                if (!stopped.get()) SwingUtilities.invokeLater(() -> {
                    if (!stopped.get()) shell.dashboard().setWeather(new WeatherSnapshot("", "", "", "Weather unavailable"));
                });
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    private static String appendStatus(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank() || first.equalsIgnoreCase(second)) return first;
        return first + " · " + second;
    }

    private static final class Arguments {
        private Path config = Path.of("config.toml");
        private String search;
        private boolean checkFeed;
        private boolean help;

        private static Arguments parse(String[] args) {
            Arguments result = new Arguments();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help", "-h" -> result.help = true;
                    case "--config" -> {
                        if (++i >= args.length) throw new IllegalArgumentException("--config needs a file path");
                        result.config = Path.of(args[i]);
                    }
                    case "--search-stops" -> {
                        if (++i >= args.length || args[i].isBlank()) throw new IllegalArgumentException("--search-stops needs a search term");
                        result.search = args[i];
                    }
                    case "--check-feed" -> result.checkFeed = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i] + " (use --help)");
                }
            }
            if (result.search != null && result.checkFeed) throw new IllegalArgumentException("Choose --search-stops or --check-feed, not both");
            return result;
        }
    }
}
