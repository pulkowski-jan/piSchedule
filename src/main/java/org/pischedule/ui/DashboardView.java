package org.pischedule.ui;

import org.pischedule.config.AppConfig;
import org.pischedule.transit.Departure;
import org.pischedule.transit.TransitSnapshot;
import org.pischedule.weather.WeatherSnapshot;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A single, passive view. All changes to its immutable snapshots happen on the EDT.
 */
public final class DashboardView extends JPanel {
    private static final Color BACKGROUND = new Color(15, 20, 26);
    private static final Color PANEL = new Color(29, 38, 48);
    private static final Color TEXT = new Color(244, 248, 251);
    private static final Color MUTED = new Color(188, 201, 211);
    private static final Color ACCENT = new Color(93, 201, 197);
    private static final Color ACCENT2 = new Color(166, 125, 184);
    private static final Color WARNING = new Color(255, 211, 114);
    private static final Color ROW_BACKGROUND = new Color(22, 30, 38);

    private static final int DEPARTURE_COLUMNS = 2;
    private static final int TIMES_PER_GROUP = 3;
    private static final int MINIMUM_ROW_HEIGHT = 48;
    private static final int PREFERRED_ROW_HEIGHT = 68;
    private static final String TIME_SEPARATOR = "   ";
    // Kraków stop codes such as 553-01 consist of a stop group and platform.
    public static final Pattern KRAKOW_STOP_CODE_PATTERN = Pattern.compile("[0-9]+-[0-9]+");

    private final AppConfig.Display display;
    private final String weatherLocation;
    private final DateTimeFormatter timeFormatter;
    private final Timer clock;
    private TransitSnapshot transit;
    private WeatherSnapshot weather = new WeatherSnapshot("", "", "", "Weather loading");

    public DashboardView(AppConfig.Display display, String stopName, String weatherLocation) {
        this.display = display;
        this.weatherLocation = weatherLocation;
        this.timeFormatter = DateTimeFormatter.ofPattern(display.timeFormat(), Locale.getDefault());
        this.transit = new TransitSnapshot(List.of(), stopName, "Loading timetable");
        setBackground(BACKGROUND);
        setForeground(TEXT);
        setPreferredSize(new Dimension(800, 480));
        clock = new Timer(1000, event -> repaint());
        clock.start();
    }

    public void setTransit(TransitSnapshot snapshot) {
        requireEdt();
        transit = snapshot;
        repaint();
    }

    public void setWeather(WeatherSnapshot snapshot) {
        requireEdt();
        weather = snapshot;
        repaint();
    }

    public void stop() {
        requireEdt();
        clock.stop();
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Dashboard updates must run on the Swing event-dispatch thread");
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (getWidth() < 100 || getHeight() < 100) {
            return;
        }

        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            DashboardLayout layout = DashboardLayout.calculate(
                    getWidth(), getHeight(), display.textScale(), hasText(weather.status()));
            Instant now = Instant.now();

            drawHeader(g, layout);
            drawTitle(g, layout);
            drawDepartures(g, layout, now);
            drawFooter(g, layout);
        } finally {
            g.dispose();
        }
    }

    private void drawHeader(Graphics2D g, DashboardLayout layout) {
        g.setColor(PANEL);
        g.fillRect(0, 0, layout.width(), layout.headerHeight());
        drawClock(g, layout);
        drawWeather(g, layout);
    }

    private void drawClock(Graphics2D g, DashboardLayout layout) {
        g.setFont(font(Font.BOLD, Math.min(layout.headerHeight() * 0.64, 38 * layout.scale())));
        g.setColor(TEXT);
        FontMetrics metrics = g.getFontMetrics();
        int baseline = (layout.headerHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
        drawClipped(g, clockText(), layout.margin(), baseline, layout.clockWidth());
    }

    private String clockText() {
        ZonedDateTime localTime = ZonedDateTime.now(display.timeZone());
        try {
            return timeFormatter.format(localTime);
        } catch (RuntimeException invalidForThisInstant) {
            // A valid pattern can still fail on a value wider than its pad width.
            return DateTimeFormatter.ofPattern("HH:mm").format(localTime);
        }
    }

    private void drawWeather(Graphics2D g, DashboardLayout layout) {
        // The clock font is also the original glyph-availability check for the icon.
        String icon = join(weather.icon(), weather.condition());
        String text = join(weather.temperature(), icon);
        text = text.isBlank() || !weather.status().isBlank() ? fallbackWeatherText() : join(weatherLocation, text);

        int left = Math.min(layout.width() - layout.margin(), layout.clockWidth() + 2 * layout.margin());
        int right = layout.width() - layout.margin();
        g.setFont(font(Font.BOLD, Math.min(layout.headerHeight() * 0.36, 22 * layout.scale())));
        g.setColor(TEXT);
        drawRightClipped(g, text, left, right, layout.headerHeight() / 2);
        g.setFont(font(Font.PLAIN, Math.min(layout.headerHeight() * 0.18, 11 * layout.scale())));
        g.setColor(MUTED);
        int attributionBaseline = layout.headerHeight() - Math.max(4, layout.margin() / 2);
        drawRightClipped(g, "https://open-meteo.com/", left, right, attributionBaseline);
    }

    private String fallbackWeatherText() {
        return weatherLocation.isBlank() ? "No weather" : weatherLocation;
    }

    private void drawTitle(Graphics2D g, DashboardLayout layout) {
        g.setColor(TEXT);
        g.setFont(font(Font.BOLD, Math.min(layout.titleHeight() * 0.65, 24 * layout.scale())));
        String title = hasText(transit.stopName()) ? transit.stopName() : "Departures";
        drawClipped(g, title, layout.margin(), layout.rowsTop() - 7, layout.innerWidth());
    }

    private void drawDepartures(Graphics2D g, DashboardLayout layout, Instant now) {
        List<List<Departure>> upcoming = departureGroups(transit.departures(), now);
        List<List<List<Departure>>> rows = departureRows(upcoming, display.departureLimit());
        List<Integer> heights = rowHeights(layout.available(), layout.minimumRowHeight(), rows.size());
        if (heights.isEmpty()) {
            drawEmptyDepartures(g, layout, !upcoming.isEmpty());
            return;
        }

        int y = layout.rowsTop();
        for (int i = 0; i < heights.size(); i++) {
            int height = heights.get(i);
            drawDepartureRow(g, rows.get(i), layout, now, i, y, height);
            y += height;
        }
    }

    private void drawEmptyDepartures(Graphics2D g, DashboardLayout layout, boolean hasUpcoming) {
        if (layout.available() <= 15) return;
        g.setColor(MUTED);
        g.setFont(font(Font.PLAIN, Math.min(20 * layout.scale(), Math.max(12, layout.available() * 0.35))));
        int baseline = layout.rowsTop() + Math.min(layout.minimumRowHeight() - 4, layout.available() - 2);
        drawClipped(g, emptyDeparturesMessage(hasUpcoming), layout.margin(), baseline, layout.innerWidth());
    }

    private String emptyDeparturesMessage(boolean hasUpcoming) {
        if (hasUpcoming) return "Increase display space";
        String state = transit.status() == null ? "" : transit.status().toLowerCase(Locale.ROOT);
        if (state.contains("loading")) return "Loading departures…";
        if (state.contains("expired")) return "Timetable expired";
        if (state.contains("unavailable") || state.contains("failed") || state.contains("no cache")) {
            return "Departures unavailable";
        }
        return "No upcoming departures";
    }

    private void drawDepartureRow(Graphics2D g, List<List<Departure>> groups, DashboardLayout layout, Instant now,
                                  int index, int y, int height) {
        int contentHeight = Math.min(height, layout.preferredRowHeight());
        int contentY = y + (height - contentHeight) / 2;
        if ((index & 1) == 0) {
            g.setColor(ROW_BACKGROUND);
            g.fillRoundRect(layout.margin(), y, layout.innerWidth(), height - 2, 6, 6);
        }

        for (int column = 0; column < groups.size(); column++) {
            drawDepartureGroup(g, groups.get(column), now, layout.columnX(column), contentY,
                    layout.columnWidth(), contentHeight, layout.scale(), layout.margin());
        }
    }

    private void drawFooter(Graphics2D g, DashboardLayout layout) {
        g.setFont(font(Font.PLAIN, Math.min(15 * layout.scale(), 16)));
        boolean transitWarning = hasText(transit.status());
        boolean weatherWarning = hasText(weather.status());
        String text = transitWarning ? "Scheduled · " + transit.status() : "Scheduled";
        int bottomBaseline = layout.height() - Math.max(5, layout.margin() / 2);
        int transitBaseline = bottomBaseline - (weatherWarning ? g.getFontMetrics().getHeight() : 0);

        g.setColor(transitWarning ? WARNING : MUTED);
        drawClipped(g, text, layout.margin(), transitBaseline, layout.innerWidth());
        if (weatherWarning) {
            g.setColor(WARNING);
            drawClipped(g, weather.status(), layout.margin(), bottomBaseline, layout.innerWidth());
        }
    }

    /**
     * All panel geometry is calculated in one place, independently of drawing.
     */
    private record DashboardLayout(int width, int height, double scale, int margin,
                                   int headerHeight, int titleHeight, int available) {
        static DashboardLayout calculate(int width, int height, double scale, boolean weatherWarning) {
            int margin = Math.max(6, Math.round(width * 0.018f));
            int header = Math.max(44, Math.min(height / 4, (int) (height * 0.18)));
            int title = Math.max(32, Math.min(height / 4, (int) (38 * scale)));
            int footer = Math.max(25, Math.min(height / 4, (int) ((weatherWarning ? 42 : 27) * scale)));
            int available = Math.max(0, height - header - title - footer - margin);
            return new DashboardLayout(width, height, scale, margin, header, title, available);
        }

        int rowsTop() {
            return headerHeight + titleHeight;
        }

        int innerWidth() {
            return width - 2 * margin;
        }

        int clockWidth() {
            return Math.max(1, (int) (width * 0.40) - margin);
        }

        int preferredRowHeight() {
            return Math.max(MINIMUM_ROW_HEIGHT, (int) (PREFERRED_ROW_HEIGHT * scale));
        }

        int minimumRowHeight() {
            return available >= MINIMUM_ROW_HEIGHT ? Math.min(preferredRowHeight(), available) : preferredRowHeight();
        }

        int columnWidth() {
            return Math.max(0, (innerWidth() - margin) / DEPARTURE_COLUMNS);
        }

        int columnX(int column) {
            return margin + column * (columnWidth() + margin);
        }
    }

    private void drawDepartureGroup(Graphics2D graphics, List<Departure> group, Instant now,
                                    int x, int y, int width, int height, double scale, int margin) {
        if (width <= 0) return;
        Graphics2D g = (Graphics2D) graphics.create(x, y, width, height - 2);
        try {
            int padding = Math.max(4, margin / 2);
            int lineWidth = Math.min(Math.max(28, (int) (width * 0.12)), Math.max(0, width - padding * 2));
            int headingX = padding + lineWidth;
            int headingWidth = Math.max(0, width - headingX - padding);

            g.setFont(font(Font.BOLD, Math.min(height * 0.34, 23 * scale)));
            g.setColor(ACCENT);
            shrinkFontToFit(g, group.get(0).line(), Math.max(0, lineWidth - padding), 10);
            drawClipped(g, group.get(0).line(), padding, headingBaseline(height), lineWidth - padding);

            if (headingWidth > 0) {
                Graphics2D heading = (Graphics2D) g.create(headingX, 0, headingWidth, height - 2);
                try {
                    drawGroupHeading(heading, group.get(0), headingWidth, height, scale);
                } finally {
                    heading.dispose();
                }
            }
            int timeWidth = Math.max(0, width - 2 * padding);
            if (timeWidth > 0) {
                Graphics2D times = (Graphics2D) g.create(padding, 0, timeWidth, height - 2);
                try {
                    drawGroupTimes(times, group, now, timeWidth, height, scale);
                } finally {
                    times.dispose();
                }
            }
        } finally {
            g.dispose();
        }
    }

    private void drawGroupHeading(Graphics2D g, Departure first, int width, int height, double scale) {
        g.setColor(TEXT);
        g.setFont(font(Font.PLAIN, Math.min(height * 0.30, 21 * scale)));
        String heading = join(first.destination(), boardingLabel(first.stopName(), first.platform()));
        shrinkFontToFit(g, heading, width, 14);
        g.drawString(fitDestination(g.getFontMetrics(), first.destination(), first.stopName(),
                first.platform(), width), 0, headingBaseline(height));
    }

    private void drawGroupTimes(Graphics2D g, List<Departure> group, Instant now,
                                int width, int height, double scale) {
        String times = departureTimes(group, now);
        g.setFont(font(Font.BOLD, Math.min(height * 0.28, 19 * scale)));
        shrinkFontToFit(g, times, width, 10);
        int baseline = height - 10;
        g.setColor(MUTED);
        drawClipped(g, times, 0, baseline, width);
        // Highlight the first countdown without changing the compact text spacing.
        g.setColor(ACCENT2);
        drawClipped(g, countdown(now, group.get(0).time()), 0, baseline, width);
    }

    private static int headingBaseline(int contentHeight) {
        return contentHeight / 2 - 5;
    }

    private static void shrinkFontToFit(Graphics2D g, String text, int width, float minimumSize) {
        while (g.getFontMetrics().stringWidth(text) > width && g.getFont().getSize2D() > minimumSize) {
            g.setFont(g.getFont().deriveFont(Math.max(minimumSize, g.getFont().getSize2D() - 1)));
        }
    }

    /**
     * Share all available height among the rows that fit, including leftover pixels.
     */
    static List<Integer> rowHeights(int available, int minimumHeight, int requestedRows) {
        int count = Math.min(requestedRows, Math.max(0, available) / Math.max(1, minimumHeight));
        if (count <= 0) return List.of();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            heights.add(available / count + (i < available % count ? 1 : 0));
        }
        return List.copyOf(heights);
    }

    /**
     * Compact, text-sized spacing instead of stretching times across the entire column.
     */
    static String departureTimes(List<Departure> group, Instant now) {
        if (group.isEmpty()) return "";
        Departure first = group.get(0);
        List<String> times = new ArrayList<>();
        for (Departure next : group) {
            if (sameBoardingDetails(next, first)) {
                times.add(countdown(now, next.time()));
            } else if (Objects.equals(first.destination(), next.destination())) {
                times.add(join(countdown(now, next.time()), boardingLabel(next.stopName(), next.platform())));
            }
        }
        return String.join(TIME_SEPARATOR, times);
    }

    private static boolean sameBoardingDetails(Departure first, Departure second) {
        return Objects.equals(first.destination(), second.destination())
                && Objects.equals(first.stopName(), second.stopName())
                && Objects.equals(first.platform(), second.platform());
    }

    /**
     * Arrange ordered groups left to right, with at most two groups per row.
     */
    static List<List<List<Departure>>> departureRows(List<List<Departure>> groups, int limit) {
        List<List<Departure>> visible = groups.stream().limit(limit).toList();
        List<List<List<Departure>>> rows = new ArrayList<>();
        for (int start = 0; start < visible.size(); start += DEPARTURE_COLUMNS) {
            int end = Math.min(start + DEPARTURE_COLUMNS, visible.size());
            rows.add(List.copyOf(visible.subList(start, end)));
        }
        return List.copyOf(rows);
    }

    /**
     * Groups ordered by their earliest visible departure, with three times per group.
     */
    static List<List<Departure>> departureGroups(List<Departure> departures, Instant now) {
        Map<GroupKey, List<Departure>> groups = new LinkedHashMap<>();
        departures.stream().filter(departure -> isVisible(departure, now))
                .sorted(Comparator.comparing(Departure::time).thenComparing(Departure::line)
                        .thenComparing(Departure::destination))
                .forEach(departure -> {
                    GroupKey key = GroupKey.of(departure);
                    List<Departure> group = groups.computeIfAbsent(key, ignored -> new ArrayList<>());
                    if (group.size() < TIMES_PER_GROUP) {
                        group.add(departure);
                    }
                });
        return groups.values().stream().map(List::copyOf).toList();
    }

    private static boolean isVisible(Departure departure, Instant now) {
        return departure.isVisibleAt(now) && departure.time().isBefore(now.plus(121, ChronoUnit.MINUTES));
    }

    private record GroupKey(String line, Integer direction, String destination) {
        static GroupKey of(Departure departure) {
            return new GroupKey(departure.line(), departure.direction(), departure.destination());
        }
    }

    private Font font(int style, double size) {
        return getFont().deriveFont(style, (float) Math.max(10, size));
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static String join(String first, String second) {
        return ((first == null ? "" : first) + " " + (second == null ? "" : second)).trim();
    }

    /**
     * Format and fit the destination while keeping the boarding point after it.
     */
    static String fitDestination(FontMetrics metrics, String destination, String stopName, String platform, int width) {
        if (width <= 0) return "";
        String direction = bounded(destination).strip();
        String name = bounded(stopName).strip();
        String point = shortPlatform(platform);
        String label = boardingLabel(name, point);
        String full = join(direction, label);
        if (metrics.stringWidth(full) <= width) return full;
        if (label.isEmpty()) return fit(metrics, direction, width);
        if (direction.isEmpty()) return fitBoardingLabel(metrics, name, point, width);

        int separatorWidth = metrics.stringWidth(" ");
        // Reserve some space for the direction as well as the boarding point.
        int directionMinimum = Math.min(metrics.stringWidth(direction),
                Math.max(metrics.stringWidth("…"), (width - separatorWidth) / 3));
        int labelBudget = width - separatorWidth - directionMinimum;
        if (labelBudget <= 0) return fit(metrics, full, width);
        String fittedLabel = fitBoardingLabel(metrics, name, point, labelBudget);
        if (fittedLabel.isEmpty()) return fit(metrics, full, width);
        int directionWidth = width - separatorWidth - metrics.stringWidth(fittedLabel);
        return join(fit(metrics, direction, directionWidth), fittedLabel);
    }

    static String boardingLabel(String stopName, String platform) {
        String point = bounded(platform).strip();
        String contents;
        if (KRAKOW_STOP_CODE_PATTERN.matcher(point).matches()) {
            contents = join(bounded(stopName).strip(), point.substring(point.indexOf('-') + 1));
        } else {
            contents = point;
        }
        return contents.isEmpty() ? "" : "(" + contents + ")";
    }

    private static String fitBoardingLabel(FontMetrics metrics, String name, String point, int width) {
        String label = boardingLabel(name, point);
        if (metrics.stringWidth(label) <= width) return label;
        if (name.isEmpty()) return fit(metrics, label, width);
        String suffix = point.isEmpty() ? ")" : " " + point + ")";
        int nameWidth = width - metrics.stringWidth("(" + suffix);
        if (nameWidth < metrics.stringWidth("…")) return fit(metrics, label, width);
        return "(" + fit(metrics, name, nameWidth) + suffix;
    }

    private static String shortPlatform(String platform) {
        String value = bounded(platform).strip();
        if (KRAKOW_STOP_CODE_PATTERN.matcher(value).matches()) {
            return value.substring(value.indexOf('-') + 1);
        }
        return value;
    }

    private static String bounded(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= 512) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(511)) ? 511 : 512;
        return text.substring(0, end) + "…";
    }

    static String countdown(Instant now, Instant departure) {
        if (departure == null) {
            return "—";
        }
        if (!departure.isAfter(now)) {
            return "<1 min";
        }
        Duration remaining = Duration.between(now, departure);
        long seconds = remaining.getSeconds();
        long minutes = seconds / 60;
        if (seconds % 60 != 0 || remaining.getNano() != 0) {
            minutes++;
        }
        return minutes + " min";
    }

    private static void drawRightClipped(Graphics2D g, String text, int left, int right, int baseline) {
        if (right <= left) return;
        String fitted = fit(g.getFontMetrics(), text, right - left);
        g.drawString(fitted, right - g.getFontMetrics().stringWidth(fitted), baseline);
    }

    private static void drawClipped(Graphics2D g, String text, int x, int baseline, int width) {
        if (width > 0) g.drawString(fit(g.getFontMetrics(), text, width), x, baseline);
    }

    private static String fit(FontMetrics metrics, String text, int width) {
        if (text == null) return "";
        // Bound rendering work even if a feed supplies an unexpectedly long field.
        boolean truncated = text.length() > 512;
        if (truncated) {
            int end = Character.isHighSurrogate(text.charAt(511)) ? 511 : 512;
            text = text.substring(0, end);
        }
        if (!truncated && metrics.stringWidth(text) <= width) return text;
        String ellipsis = "…";
        if (metrics.stringWidth(ellipsis) > width) return "";
        int low = 0;
        int high = text.codePointCount(0, text.length());
        while (low < high) {
            int middle = (low + high + 1) / 2;
            int end = text.offsetByCodePoints(0, middle);
            if (metrics.stringWidth(text.substring(0, end) + ellipsis) <= width) low = middle;
            else high = middle - 1;
        }
        return text.substring(0, text.offsetByCodePoints(0, low)) + ellipsis;
    }
}
