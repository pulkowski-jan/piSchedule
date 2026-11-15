package org.pischedule;

import org.pischedule.config.AppConfig;
import org.pischedule.logging.AppLogging;
import org.pischedule.transit.Stop;
import org.pischedule.transit.TransitService;
import org.pischedule.transit.TransitSnapshot;
import org.pischedule.weather.WeatherService;

import java.awt.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.*;

public final class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    private Main() {}

    public static void main(String[] args) {
        try {
            AppLogging.configure(null);
            Arguments options = Arguments.parse(args);
            if (options.help) {
                LOGGER.info(
                        "Usage: java -jar piSchedule.jar [--config FILE] [--search-stops TEXT |"
                                + " --check-feed]");
                return;
            }
            AppConfig config = AppConfig.load(options.config);
            AppLogging.configure(config.logFile());
            TransitService transit = new TransitService(config.transit(), config.cacheDirectory());
            if (options.search != null) {
                runSearch(transit, options);
                return;
            }
            if (options.checkFeed) {
                runFeedCheck(transit, config);
                return;
            }
            if (GraphicsEnvironment.isHeadless()) {
                throw new IllegalStateException(
                        "A graphical environment with AWT/Swing support is required");
            }
            WeatherService weather = new WeatherService(config.weather(), config.cacheDirectory());
            DashboardApp dashboardApp = new DashboardApp(config, transit, weather);
            SwingUtilities.invokeLater(dashboardApp::start);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "piSchedule: " + e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void runFeedCheck(TransitService transit, AppConfig config) throws Exception {
        transit.update();
        TransitSnapshot result = transit.snapshot(Instant.now(), config.display().departureLimit());
        LOGGER.info("Stop: " + result.stopName());
        LOGGER.info("Status: " + (result.status().isBlank() ? "ok" : result.status()));
        for (var departure : result.departures()) {
            LOGGER.info(
                    "%s\t%s\t%s\t%s\t%s\t%d"
                            .formatted(
                                    departure.time().atZone(config.display().timeZone()),
                                    departure.line(),
                                    departure.destination(),
                                    departure.stopName(),
                                    departure.platform(),
                                    departure.direction() != null ? departure.direction() : -1));
        }
    }

    private static void runSearch(TransitService transit, Arguments options) throws Exception {
        for (Stop stop : transit.searchStops(options.search)) {
            LOGGER.info(
                    "%s\t%s\tparent=%s\tplatform=%s"
                            .formatted(
                                    stop.id(),
                                    stop.name(),
                                    stop.parentId() == null ? "" : stop.parentId(),
                                    stop.platform() == null ? "" : stop.platform()));
        }
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
                        if (++i >= args.length) {
                            throw new IllegalArgumentException("--config needs a file path");
                        }
                        result.config = Path.of(args[i]);
                    }
                    case "--search-stops" -> {
                        if (++i >= args.length || args[i].isBlank()) {
                            throw new IllegalArgumentException(
                                    "--search-stops needs a search term");
                        }
                        result.search = args[i];
                    }
                    case "--check-feed" -> result.checkFeed = true;
                    default ->
                            throw new IllegalArgumentException(
                                    "Unknown argument: " + args[i] + " (use --help)");
                }
            }
            if (result.search != null && result.checkFeed) {
                throw new IllegalArgumentException(
                        "Choose --search-stops or --check-feed, not both");
            }
            return result;
        }
    }
}
