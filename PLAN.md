# piSchedule implementation plan

## Goal

Build a lightweight, full-screen transit dashboard for a Raspberry Pi 3 Model B. It must run on 32-bit ARM with low RAM use and show upcoming scheduled tram departures, the current time, and weather.

## Agreed requirements

- Download GTFS data from a configurable URL.
- Default feed: https://gtfs.ztp.krakow.pl/GTFS_KRK_T.zip (Kraków trams).
- Default stop: Łagiewniki, covering all of its tram platforms and directions.
- Make line and per-line direction filtering configurable; default to no filtering.
- Show the current local time.
- Show weather for Kraków by default, with a configurable location.
- Target a touchscreen with a physical display area of approximately 96 × 55 mm, or 4.4 inches diagonally. Pixel resolution and screen model are not yet known.
- Implement one dashboard only. Keep its design compatible with future touch-based screen switching, but do not add extra screens or navigation controls now.
- Keep settings in an external configuration file. Do not add on-screen settings.
- Use scheduled departures initially. Live predictions are outside the first-version scope.

## Selected technology and deployment

- Java with Swing for the graphical interface.
- Use a supported, distribution-provided OpenJDK available for 32-bit ARM. Confirm runtime availability on the chosen Raspberry Pi OS image before fixing the Java language/API baseline.
- Install a runtime with AWT/Swing support, not only a headless Java runtime.
- SQLite for the local timetable cache. Verify that the selected JDBC driver includes a compatible ARM32 native library, or can use a compatible system library, before pinning its version.
- TOML configuration using a small parsing library.
- Use Java's built-in HTTP client, streaming ZIP APIs, and java.time support. Use small, maintained libraries for streaming CSV parsing and weather JSON parsing.
- Avoid Chromium, Electron, JavaFX deployment dependencies, and heavyweight application frameworks.
- Recommended operating system: Raspberry Pi OS Lite, 32-bit, with a minimal X11 session and Openbox.
- Start the application automatically in full-screen mode and disable screen blanking for the dedicated dashboard session.
- Document screen-driver and touch setup as hardware-dependent steps.

## Build and packaging

- Use Gradle with pinned dependency and plugin versions and automated tests using JUnit.
- Produce a runnable JAR with its required dependencies, buildable on a desktop for deployment to the Pi.
- Set the compiler release target to the selected Pi runtime baseline; do not assume the desktop JDK version matches the Pi's runtime.
- Verify ARM32 compatibility for all native dependencies. Portable Java bytecode alone does not guarantee that bundled native libraries will work.
- Keep build tooling off the Pi where practical; deploy the application artifact and configuration alongside the installed runtime.

## Dashboard design

- Detect available pixel dimensions at startup rather than assuming a resolution from physical dimensions.
- Use a compact header with HH:MM, a weather condition icon, and temperature.
- Show the stop name and departure rows prioritizing line, destination, and departure countdown or time.
- Show platform identifiers where available and useful for distinguishing boarding points.
- Combine departures across selected platforms and sort them chronologically.
- Adapt the number of rows to available space without making text excessively small.
- Provide configurable text scaling and a departure-count limit.
- Use high-contrast styling and avoid animations.
- Clearly identify departures as scheduled, not live predictions.
- Provide concise states for initial loading, no upcoming departures, unavailable weather, stale data, and expired timetable coverage.
- Keep dashboard rendering separate from data services so additional touch-navigable screens can be added later without redesigning the backend.

## Touchscreen readiness and future navigation

- Separate the full-screen application shell, dashboard view, and timetable/weather services. Implement only the dashboard initially; do not add placeholder screens or a navigation framework.
- Let the shell host additional views later, using a simple mechanism such as Swing CardLayout. Switching views must not recreate data services or trigger unnecessary downloads.
- Support ordinary single-finger taps through the operating system's mouse-event mapping, subject to verification with the actual touchscreen driver.
- Do not require hover, right-click, double-click, or text entry on the touchscreen.
- Keep UI scaling configurable because reported display DPI may be inaccurate. For future controls, aim for roughly 8–10 mm touch targets and validate their physical size on the device.
- If a second screen is added later, consider horizontal swipes or large navigation buttons. Do not implement either in version one.
- Future swipe recognition should use display-scaled movement thresholds, distinguish horizontal from vertical movement, and prevent a completed swipe from also activating a control.
- Account for Swing mouse events not automatically bubbling through parent components when designing future gesture handling across labels and other child components.
- Keep future screen transitions immediate and lightweight rather than animated.
- Multi-touch and pinch gestures are not required. Standard Swing does not provide a portable multi-contact touch API.
- Configure screen rotation and touch-coordinate alignment at the OS/input-driver level. Verify corner taps, press/drag/release behavior, and mouse compatibility on the device.

## Configuration

The configuration file should support:

- GTFS URL and update-check interval.
- Stop name and optional explicit stop/platform IDs.
- Optional line allowlist.
- Optional destination/headsign filters per line.
- Optional GTFS direction IDs for advanced filtering, scoped to individual lines.
- Weather coordinates, location label, units, and refresh interval.
- Display time zone and time format.
- Full-screen behavior, text scaling, and departure-count limit.

Default weather location is Kraków; its coordinates remain editable. Use Europe/Warsaw for the initial display time zone, while interpreting transit schedules according to the feed's agency time zone.

Empty line and direction filters mean all lines and directions. Do not treat GTFS direction_id values as globally meaningful geographic directions.

## GTFS discovery and departure calculation

1. Inspect the actual feed's stop records before selecting platform IDs.
2. Provide a command-line stop-search function so platform IDs can be discovered without on-screen settings.
3. Resolve Łagiewniki through exact names, explicit IDs, and station/platform relationships where provided. Do not silently include similarly named nearby stops through substring matching.
4. Store departures for selected stops along with the route, trip, service-calendar, and platform information needed to query them.
5. Account for:
   - Weekly service calendars.
   - Added and removed services in calendar_dates.txt.
   - Feeds that express service dates using calendar exceptions without a weekly calendar.
   - Agency time zones and daylight-saving transitions.
   - GTFS service-day times beyond 24:00 and departures belonging to preceding service days.
   - Stop-level destination/headsign overrides.
   - Boarding restrictions, excluding arrival-only calls from normal departure listings.
   - Multiple platforms and per-line direction filters.
6. Validate feed coverage and required fields. Report unsupported schedule features rather than displaying misleading departure times.

Exact platform IDs and feed-specific assumptions have not yet been validated against the archive contents.

## Low-memory import and caching

- Stream downloads to disk rather than retaining complete archives in RAM.
- Apply request timeouts, bounded retries, and sensible archive/download limits.
- Read ZIP entries and CSV rows incrementally.
- Ignore unused data such as shapes and route geometry.
- Retain only selected-stop schedule data and its required dependencies where practical.
- Use batched SQLite writes and a bounded database cache.
- Build and validate a replacement database before switching away from the working cache.
- Keep the previous valid cache if download, validation, or import fails.
- Use HTTP validators such as ETag or Last-Modified when supported, avoiding unnecessary reimports.
- Keep the dashboard working from cached data when offline, while clearly warning about expired timetable coverage.
- Run downloads, imports, and weather requests in bounded executors or SwingWorker tasks. Perform all Swing component updates on the event-dispatch thread; never block it on network, database imports, or other long-running operations.
- Pass small, immutable result snapshots to the GUI rather than exposing mutable background-worker state.
- Avoid overlap between update jobs, bound task queues, and handle clean shutdown.

Memory target: approximately under 100 MiB for the application during normal operation, with bounded import memory. This remains an aspirational, unmeasured target, excludes the graphical environment, and must be verified on the Raspberry Pi.

Measure total process resident memory, not only Java heap usage. JVM metadata, compiled code, thread stacks, native GUI libraries, and SQLite allocations also consume RAM. Evaluate a small starting heap and Serial GC, for example -Xms16m -Xmx64m -XX:+UseSerialGC, then tune against actual startup, import, and responsiveness measurements. The heap limit is not a cap on total process memory.

## Clock and weather

- Use the operating system clock and document network time synchronization during setup.
- Refresh the clock independently of network activity.
- Use Open-Meteo as the initial weather provider, subject to its applicable terms and attribution requirements.
- Initially display temperature and condition only; add other fields only if screen space permits.
- Refresh weather approximately every 15 minutes, configurable.
- Cache the last successful result and show a stale indicator after an appropriate age or failed refreshes.
- Handle missing or malformed weather responses without disrupting departures or the clock.
- Include appropriate weather and transit data attribution in documentation and, where required, the interface.

## Implementation sequence

1. Confirm a supported ARM32 OpenJDK and SQLite JDBC compatibility; select the Java baseline and pin dependencies.
2. Create the Gradle project, runnable-JAR packaging, JUnit setup, configuration loading, validation, and example configuration.
3. Implement streaming GTFS download, stop discovery, and SQLite cache import.
4. Implement and test service-date handling, departure queries, and filtering.
5. Build the compact Swing dashboard, application shell, and clock, maintaining event-dispatch-thread safety and separation from data services.
6. Add weather retrieval, stale-data states, offline behavior, and safe background updates.
7. Add minimal 32-bit Raspberry Pi runtime, deployment, and autostart instructions.
8. Validate total process memory, import performance, readability, and touch behavior on the actual device; tune JVM and database limits based on measurements.

## Tests and acceptance criteria

Automated tests should use small local GTFS fixtures and mocked HTTP responses, covering:

- Weekly calendars and service-date exceptions.
- After-midnight service and daylight-saving boundaries.
- Multiple platforms and exact stop selection.
- Destination overrides, boarding restrictions, and per-line filtering.
- Corrupt or incomplete archives and failed HTTP requests.
- Preservation of the previous cache after failed updates.
- Offline startup, empty departure results, and expired timetable coverage.
- Weather caching, stale indicators, and malformed responses.
- Configuration validation and helpful error messages.

Device acceptance should verify:

- Successful installation and launch of the desktop-built JAR on Raspberry Pi 3 Model B with a supported 32-bit OpenJDK and working SQLite JDBC native library.
- Automatic full-screen startup.
- Readable layout on the actual 96 × 55 mm display.
- Compatibility with the installed touch-input driver, including corner alignment, press/drag/release events, and rotation where applicable.
- Responsive clock and interface during imports and network failures, with Swing updates confined to the event-dispatch thread.
- Measured steady-state and peak import process resident memory, alongside heap usage, plus acceptable startup and query times.

## Explicitly out of scope for version one

- GTFS-Realtime predictions, delays, cancellations, and vehicle positions.
- Additional screens, screen-switching controls, and gestures.
- On-screen settings or a configuration web server.
- Maps, route geometry rendering, and animations.

The operator publishes separate realtime feeds, so a later version may add live information without changing the initial schedule-only scope.

## Remaining platform, hardware, and feed validation

- Verify supported 32-bit OpenJDK package availability for the selected OS image and choose the compiler release target accordingly.
- Verify SQLite JDBC native-library compatibility on the actual ARM32 runtime.
- Identify the touchscreen model, pixel resolution, orientation, and driver.
- Confirm final font scaling and row count on the device.
- Confirm actual Łagiewniki platform IDs from the feed before implementation defaults are finalized.
