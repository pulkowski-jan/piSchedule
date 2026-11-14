package org.pischedule.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.pischedule.config.AppConfig;
import org.pischedule.transit.Departure;
import org.pischedule.transit.TransitSnapshot;
import org.pischedule.weather.WeatherSnapshot;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import javax.swing.SwingUtilities;

class DashboardViewTest {
    @Test
    void paintsSmallAndLargeScreensAndDifferentStatesOnEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AppConfig.Display display =
                    new AppConfig.Display(ZoneId.of("Europe/Warsaw"), "HH:mm", false, 1.0, 8);
            DashboardView view = new DashboardView(display, "Łagiewniki", "Kraków");
            try {
                view.setWeather(new WeatherSnapshot("12 °C", "Cloudy", "☁", ""));
                Instant now = Instant.now();
                view.setTransit(
                        new TransitSnapshot(
                                List.of(
                                        new Departure(
                                                "8",
                                                "Borek Fałęcki",
                                                "Łagiewniki",
                                                "553-01",
                                                now,
                                                0),
                                        new Departure(
                                                "8",
                                                "Borek Fałęcki",
                                                "Łagiewniki",
                                                "553-01",
                                                now.plusSeconds(300),
                                                0),
                                        new Departure(
                                                "8",
                                                "Borek Fałęcki",
                                                "Łagiewniki",
                                                "553-01",
                                                now.plusSeconds(600),
                                                0),
                                        new Departure(
                                                "8",
                                                "Centre",
                                                "Łagiewniki",
                                                "553-02",
                                                now.plusSeconds(30),
                                                1)),
                                "Łagiewniki",
                                ""));
                paint(view, 320, 240);
                paint(view, 800, 480);
                view.setTransit(new TransitSnapshot(List.of(), "Łagiewniki", "Timetable expired"));
                view.setWeather(new WeatherSnapshot("", "", "", "Weather unavailable"));
                paint(view, 480, 320);
            } finally {
                view.stop();
            }
        });
    }

    @Test
    void snapshotsCannotBeSetFromWorkerThread() throws Exception {
        DashboardView[] view = new DashboardView[1];
        SwingUtilities.invokeAndWait(() ->
                view[0] =
                        new DashboardView(
                                new AppConfig.Display(
                                        ZoneId.of("Europe/Warsaw"), "HH:mm", false, 1.0, 4),
                                "Stop",
                                "Kraków"));
        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> view[0].setTransit(new TransitSnapshot(List.of(), "Stop", "")));
            assertThrows(
                    IllegalStateException.class,
                    () -> view[0].setWeather(new WeatherSnapshot("", "", "", "")));
        } finally {
            SwingUtilities.invokeAndWait(view[0]::stop);
        }
    }

    @Test
    void boardingPointAppearsAfterDestinationAndShortensKrakowStopCode() {
        assertEquals("(Łagiewniki 01)", DashboardView.boardingLabel("Łagiewniki", "553-01"));
        assertEquals("(Łagiewniki 02)", DashboardView.boardingLabel("Łagiewniki", "553-02"));
        assertEquals("(B-CODE)", DashboardView.boardingLabel("Station", "B-CODE"));
        assertEquals("(1)", DashboardView.boardingLabel("Station", "1"));
        assertEquals("", DashboardView.boardingLabel("Station", ""));
        assertEquals("", DashboardView.boardingLabel("", ""));

        BufferedImage image = new BufferedImage(400, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
            FontMetrics metrics = graphics.getFontMetrics();
            String expected = "Borek Fałęcki (Łagiewniki 01)";
            assertEquals(
                    expected,
                    DashboardView.fitDestination(
                            metrics, "Borek Fałęcki", "Łagiewniki", "553-01", 400));
            int width = metrics.stringWidth("Borek Fa… (Łagiewniki 01)");
            String clipped =
                    DashboardView.fitDestination(
                            metrics, "Borek Fałęcki", "Łagiewniki", "553-01", width);
            assertTrue(clipped.endsWith("(Łagiewniki 01)"), clipped);
            assertTrue(metrics.stringWidth(clipped) <= width, clipped);
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void departureRemainsVisibleAsNowUntilGracePeriodEnds() {
        Instant planned = Instant.parse("2026-11-09T09:00:00Z");
        Departure departure = new Departure("8", "Centre", "Łagiewniki", "553-01", planned, 0);
        assertTrue(departure.isVisibleAt(planned.minusSeconds(30)));
        assertTrue(departure.isVisibleAt(planned));
        assertTrue(departure.isVisibleAt(planned.plusSeconds(60)));
        assertFalse(departure.isVisibleAt(planned.plusSeconds(60).plusNanos(1)));
        assertEquals("1 min", DashboardView.countdown(planned.minusSeconds(30), planned));
        assertEquals("1 min", DashboardView.countdown(planned.minusNanos(1), planned));
        assertEquals("1 min", DashboardView.countdown(planned.minusSeconds(60), planned));
        assertEquals(
                "2 min", DashboardView.countdown(planned.minusSeconds(60).minusNanos(1), planned));
        assertEquals("<1 min", DashboardView.countdown(planned, planned));
        assertEquals("<1 min", DashboardView.countdown(planned.plusSeconds(60), planned));
    }

    @Test
    void groupsTimesTogetherAndKeepsDirectionsSeparate() {
        Instant now = Instant.parse("2026-11-09T09:00:00Z");
        Departure expired = new Departure("8", "Centre", "Stop", "01", now.minusSeconds(61), 0);
        Departure first = new Departure("8", "Centre", "Stop", "01", now.minusSeconds(60), 0);
        Departure second = new Departure("8", "Centre", "Stop", "01", now.plusSeconds(300), 0);
        Departure third = new Departure("8", "Centre", "Stop", "01", now.plusSeconds(600), 0);
        Departure fourth = new Departure("8", "Centre", "Stop", "01", now.plusSeconds(900), 0);
        Departure reverse = new Departure("8", "Base", "Stop", "02", now.plusSeconds(100), 1);
        Departure otherLine = new Departure("10", "Centre", "Stop", "01", now.plusSeconds(200), 0);
        assertEquals(
                List.of(List.of(first, second, third), List.of(reverse), List.of(otherLine)),
                DashboardView.departureGroups(
                        List.of(fourth, second, reverse, expired, third, otherLine, first), now));
        assertEquals(
                List.of(List.of(otherLine), List.of(second, third, fourth)),
                DashboardView.departureGroups(
                        List.of(fourth, second, third, otherLine, first), now.plusSeconds(61)));
    }

    @Test
    void differentDestinationsWithSameDirectionGetSeparateCells() {
        Instant now = Instant.parse("2026-11-09T09:00:00Z");
        Departure centre = new Departure("8", "Centre", "Stop", "01", now, 0);
        Departure branch = new Departure("8", "Branch", "Stop", "01", now.plusSeconds(20), 0);
        assertEquals(
                List.of(List.of(List.of(centre), List.of(branch))),
                DashboardView.departureRows(
                        DashboardView.departureGroups(List.of(branch, centre), now), 2));
    }

    @Test
    void missingDirectionUsesDestinationAsGroupKey() {
        Instant now = Instant.parse("2026-11-09T09:00:00Z");
        Departure centre = new Departure("8", "Centre", "Stop", "01", now, null);
        Departure base = new Departure("8", "Base", "Stop", "02", now.plusSeconds(20), null);
        assertEquals(
                List.of(List.of(centre), List.of(base)),
                DashboardView.departureGroups(List.of(base, centre), now));
    }

    @Test
    void directionGroupsFillTwoColumnsInNextDepartureOrder() {
        Instant now = Instant.parse("2026-11-09T09:00:00Z");
        Departure outbound = new Departure("8", "Centre", "Stop", "553-01", now, 0);
        Departure inbound = new Departure("8", "Base", "Stop", "553-02", now.plusSeconds(60), 1);
        Departure branch =
                new Departure("8", "Branch", "Stop", "553-03", now.plusSeconds(120), null);
        Departure otherLine = new Departure("10", "East", "Stop", "553-04", now.plusSeconds(30), 0);
        Departure secondOutbound =
                new Departure("8", "Centre", "Stop", "553-01", now.plusSeconds(300), 0);
        Departure thirdOutbound =
                new Departure("8", "Centre", "Stop", "553-01", now.plusSeconds(600), 0);
        List<Departure> outboundGroup = List.of(outbound, secondOutbound, thirdOutbound);
        List<List<Departure>> groups =
                DashboardView.departureGroups(
                        List.of(
                                branch,
                                thirdOutbound,
                                otherLine,
                                inbound,
                                secondOutbound,
                                outbound),
                        now);
        assertEquals(
                List.of(
                        List.of(outboundGroup, List.of(otherLine)),
                        List.of(List.of(inbound), List.of(branch))),
                DashboardView.departureRows(groups, 4));
        assertEquals(
                List.of(List.of(outboundGroup, List.of(otherLine)), List.of(List.of(inbound))),
                DashboardView.departureRows(groups, 3));
        assertEquals(List.of(List.of(outboundGroup)), DashboardView.departureRows(groups, 1));
        assertTrue(DashboardView.departureRows(groups, 0).isEmpty());
        assertTrue(DashboardView.departureRows(List.of(), 4).isEmpty());
    }

    @Test
    void paintsTwoColumnsAndLeavesLastCellEmptyForOddGroupCount() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DashboardView view =
                    new DashboardView(
                            new AppConfig.Display(
                                    ZoneId.of("Europe/Warsaw"), "HH:mm", false, 1.0, 8),
                            "Stop",
                            "Kraków");
            try {
                view.setWeather(new WeatherSnapshot("12 °C", "Cloudy", "", ""));
                Instant now = Instant.now();
                List<Departure> departures =
                        List.of(
                                new Departure("8", "Centre", "Stop", "01", now.plusSeconds(60), 0),
                                new Departure("10", "West", "Stop", "02", now.plusSeconds(120), 1),
                                new Departure("22", "East", "Stop", "03", now.plusSeconds(180), 0),
                                new Departure(
                                        "44", "South", "Stop", "04", now.plusSeconds(240), 1));
                view.setTransit(new TransitSnapshot(departures, "Stop", ""));
                BufferedImage four = paintImage(view, 800, 480);
                // The four regions correspond to two columns and two rows of the departure area.
                assertTrue(containsAccent(four, 14, 124, 393, 282));
                assertTrue(containsAccent(four, 407, 124, 786, 282));
                assertTrue(containsAccent(four, 14, 282, 393, 439));
                assertTrue(containsAccent(four, 407, 282, 786, 439));

                view.setTransit(new TransitSnapshot(departures.subList(0, 3), "Stop", ""));
                BufferedImage three = paintImage(view, 800, 480);
                assertTrue(containsAccent(three, 14, 282, 393, 439));
                assertFalse(containsAccent(three, 407, 282, 786, 439));
            } finally {
                view.stop();
            }
        });
    }

    @Test
    void paintsDirectionRowsWithLongHeadings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DashboardView view =
                    new DashboardView(
                            new AppConfig.Display(
                                    ZoneId.of("Europe/Warsaw"), "HH:mm", false, 1.0, 8),
                            "Łagiewniki",
                            "Kraków");
            try {
                Instant now = Instant.now();
                view.setTransit(
                        new TransitSnapshot(
                                List.of(
                                        new Departure(
                                                "8",
                                                "Borek Fałęcki",
                                                "Łagiewniki",
                                                "553-01",
                                                now.plusSeconds(10),
                                                0),
                                        new Departure(
                                                "8",
                                                "Borek Fałęcki",
                                                "Łagiewniki",
                                                "553-01",
                                                now.plusSeconds(300),
                                                0),
                                        new Departure(
                                                "8",
                                                "Borek Fałęcki",
                                                "Łagiewniki",
                                                "553-01",
                                                now.plusSeconds(600),
                                                0),
                                        new Departure(
                                                "8",
                                                "Bronowice Małe",
                                                "Łagiewniki",
                                                "553-02",
                                                now.plusSeconds(30),
                                                1),
                                        new Departure(
                                                "8",
                                                "Additional branch",
                                                "Łagiewniki",
                                                "553-03",
                                                now.plusSeconds(60),
                                                null),
                                        new Departure(
                                                "10",
                                                "Pleszów",
                                                "Łagiewniki",
                                                "553-04",
                                                now.plusSeconds(90),
                                                0)),
                                "Łagiewniki",
                                ""));
                paint(view, 320, 240);
                paint(view, 800, 480);
                paint(view, 1280, 720);
            } finally {
                view.stop();
            }
        });
    }

    @Test
    void rowsFillAvailableHeightWithoutDroppingRemainderPixels() {
        assertEquals(List.of(157, 156), DashboardView.rowHeights(313, 68, 2));
        assertEquals(List.of(313), DashboardView.rowHeights(313, 68, 1));
        assertEquals(List.of(79, 78, 78, 78), DashboardView.rowHeights(313, 68, 10));
        assertTrue(DashboardView.rowHeights(313, 68, 0).isEmpty());
        assertTrue(DashboardView.rowHeights(40, 68, 2).isEmpty());
        assertTrue(DashboardView.rowHeights(0, 68, 2).isEmpty());
    }

    @Test
    void timesUseCompactSpacingAndPreserveBoardingPointChanges() {
        Instant now = Instant.parse("2026-11-09T09:00:00Z");
        Departure first = new Departure("8", "Centre", "Stop", "553-01", now, 0);
        Departure second = new Departure("8", "Centre", "Stop", "553-01", now.plusSeconds(600), 0);
        Departure third = new Departure("8", "Centre", "Stop", "553-01", now.plusSeconds(1200), 0);
        assertEquals(
                "<1 min   10 min   20 min",
                DashboardView.departureTimes(List.of(first, second, third), now));
        Departure changed = new Departure("8", "Centre", "Stop", "553-02", now.plusSeconds(900), 0);
        assertEquals(
                "<1 min   15 min (Stop 02)",
                DashboardView.departureTimes(List.of(first, changed), now));
        Departure branch = new Departure("8", "Branch", "Stop", "553-02", now.plusSeconds(900), 0);
        assertEquals("<1 min", DashboardView.departureTimes(List.of(first, branch), now));
        assertEquals("<1 min", DashboardView.departureTimes(List.of(first), now));
        assertEquals("", DashboardView.departureTimes(List.of(), now));
    }

    @Test
    void paintsEmptyStatesWarningsAndClockFallback() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            // A one-character pad cannot hold a two-digit hour: painting must use HH:mm instead.
            DashboardView view =
                    new DashboardView(
                            new AppConfig.Display(ZoneId.of("Europe/Warsaw"), "pHH", false, 1.6, 8),
                            "",
                            "Kraków");
            try {
                view.setWeather(new WeatherSnapshot("", "", "", "Weather unavailable"));
                for (String status : List.of("loading", "expired", "failed", "unavailable", "")) {
                    view.setTransit(new TransitSnapshot(List.of(), "", status));
                    paint(view, 800, 480);
                    paint(view, 100, 100);
                }
                paint(view, 99, 99);
            } finally {
                view.stop();
            }
        });
    }

    private static boolean containsAccent(
            BufferedImage image, int left, int top, int right, int bottom) {
        int accent = new Color(93, 201, 197).getRGB();
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (image.getRGB(x, y) == accent) return true;
            }
        }
        return false;
    }

    private static void paint(DashboardView view, int width, int height) {
        paintImage(view, width, height);
    }

    private static BufferedImage paintImage(DashboardView view, int width, int height) {
        view.setSize(width, height);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            view.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
