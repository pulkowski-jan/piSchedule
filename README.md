# piSchedule

A lightweight, full-screen Swing dashboard for a Raspberry Pi 3 Model B: scheduled tram departures, local time and weather on a small touchscreen. Departures are **scheduled, not live**: delays, cancellations and vehicle positions are outside version one.

The dashboard shows line/direction groups in two columns, filled left to right in order of their next departure. Each group shows its line number, destination and boarding point, for example `8 Borek Fałęcki (Łagiewniki 01)`, with up to three departure countdowns underneath (`<1 min`, `5 min`, `15 min`). Departure times use compact spacing. Visible rows share the available departure-area height, with each heading and its times kept together. Groups are distinguished by line, direction ID and destination; trips without a direction ID are grouped by line and destination. `display.departure_limit` limits visible groups; screen space may reduce that number further. The dashboard selects groups by their next departure and keeps a small buffer for updates between queries. Numeric stop codes such as `553-01` are shortened to `01` on the dashboard. After the scheduled time, a departure remains visible as `<1 min` for one minute; this does not indicate a live delay or that the vehicle is still present.

> **Status:** The application and tests are present, but the build, end-to-end feed import and Raspberry Pi device acceptance have not yet been verified. See [PLAN.md](PLAN.md). Screen model and hardware-specific settings remain to be checked.

## Platform research and prerequisites

- Target: Pi 3 Model B, 32-bit ARM userland, Raspberry Pi OS Lite and a minimal X11/Openbox session. Raspberry Pi offers [32-bit Raspberry Pi OS Legacy Lite (Debian Bookworm)](https://www.raspberrypi.com/software/operating-systems/); use a maintained image with security updates and check its support lifetime before deployment. Current non-legacy releases may have different package versions.
- Java 17 is a plausible baseline, **subject to confirming the chosen image and device**. Raspberry Pi's [Bookworm-era armhf OpenJDK 17 build record](https://archive.raspberrypi.org/debian/buildinfo-pool/o/openjdk-17/openjdk-17_17.0.13%2B11-1~deb12u1%2Brpt1_all.buildinfo) includes `openjdk-17-jre` as well as the headless package. Install the non-headless `openjdk-17-jre`: Swing needs AWT GUI libraries and an X display. Check availability from the actual image's configured repositories; this is package evidence, **not a device launch test**. The kernel can be 64-bit while userland is 32-bit, so check `dpkg --print-architecture`, not only `uname -m`.
- [xerial sqlite-jdbc 3.46.1.3](https://github.com/xerial/sqlite-jdbc/blob/3.46.1.3/README.adoc#supported-operating-systems) lists Linux libc ARMv5, ARMv6 and ARMv7 support; its tagged source includes [ARMv6](https://github.com/xerial/sqlite-jdbc/tree/3.46.1.3/src/main/resources/org/sqlite/native/Linux/armv6) and [ARMv7](https://github.com/xerial/sqlite-jdbc/tree/3.46.1.3/src/main/resources/org/sqlite/native/Linux/armv7) JNI libraries. These are candidates for the Pi's 32-bit JVM, not proof that the final shaded JAR or device works. Verify JVM architecture detection, ARM ABI, libc compatibility and actual database operations on the target. The 3.46.1.3 source's [build flags](https://github.com/xerial/sqlite-jdbc/blob/3.46.1.3/Makefile.common) distinguish soft-float `arm` from hard-float `armv6`/`armv7`.
- The driver normally extracts a native library to a writable temporary directory, which must also permit executable mappings; `noexec` can prevent loading. Its [loader](https://github.com/xerial/sqlite-jdbc/blob/3.46.1.3/src/main/java/org/sqlite/SQLiteJDBCLoader.java) supports `-Dorg.sqlite.tmpdir=/path` and an explicit `-Dorg.sqlite.lib.path=/path` with optional `-Dorg.sqlite.lib.name=libsqlitejdbc.so`. A system alternative must be a **compatible xerial SQLite JDBC JNI bridge**, matching the Java driver and ARM ABI; the ordinary `libsqlite3.so` alone is not that bridge. Test any distribution-provided bridge and its SQLite version/features rather than assuming compatibility. Avoid replacing libraries blindly or weakening system-wide mount security.

## Prepare the Pi

The following are instructions to run on the device, not commands already executed. Flash a 32-bit Lite image using [Raspberry Pi Imager](https://www.raspberrypi.com/software/); set up a dedicated unprivileged user, networking and secure remote access if needed. Use a reliable power supply, storage and network connection. Update the OS, then install Java, X11, Openbox and basic tools:

```sh
sudo apt update
sudo apt upgrade
sudo apt install openjdk-17-jre xserver-xorg xinit openbox x11-xserver-utils xinput fonts-dejavu-core ca-certificates
```

If `openjdk-17-jre` has no installation candidate, do not substitute a headless runtime: choose a supported image/runtime with GUI support and recheck the application's compiler target. Verify the runtime and userland:

```sh
dpkg --print-architecture
uname -m
apt-cache policy openjdk-17-jre
java -version
```

Expect `armhf` for the intended 32-bit userland. Test a graphical application and an actual SQLite open/query/write on the final JAR before relying on this setup. Allow outbound HTTPS to the configured GTFS and weather providers, sufficient disk space for the downloaded ZIP, replacement database and existing cache, and write access to the application cache and temporary directory.

### Clock and time zone

Enable network time synchronization and check its actual status; install/enable a time service appropriate to the image if one is not present. For a system using systemd-timesyncd:

```sh
sudo timedatectl set-timezone Europe/Warsaw
sudo timedatectl set-ntp true
timedatectl status
timedatectl timesync-status
```

Confirm `System clock synchronized: yes` after networking is available. A Pi without a battery-backed RTC can start with an incorrect time when offline; stale clock data affects departures, weather age and TLS. The display time zone defaults to Europe/Warsaw, while schedules use the GTFS agency time zone.

## Build and deploy

On a desktop, install JDK 17 (or JDK 18–24) and Gradle 8.14.4. The Gradle build pins direct dependency and Shadow plugin versions, uses JUnit, compiles with Java `--release 17`, and produces a runnable dependency-containing JAR. The transitive dependency graph is not locked. Run from the project root:

```sh
gradle clean build
```

The build includes tests, checks that the bundled JAR contains the SQLite ARM32 libraries and JDBC service metadata, and exercises SQLite from the bundled JAR on the build host. Those checks do not replace a real device test. Dependency license and notice files included in the source JARs are retained under `META-INF/third-party/`; verify applicable licensing and attribution before distribution. The checked-in `pom.xml` is a legacy build file and is not the primary build; it can be removed.

Copy `build/libs/pischedule-1.0-SNAPSHOT-all.jar` to the Pi as `~/piSchedule/piSchedule.jar`, and copy `config.example.toml` as `~/piSchedule/config.toml`. The JAR without `-all` is a thin JAR and should not be deployed alone. The Shadow configuration preserves SQLite native resources and merges service metadata; verify the resulting JAR on the device. Gradle is not required on the Pi.

Edit the external TOML configuration; `config.example.toml` documents each setting. Relative `cache_directory` paths resolve against the configuration file's directory. Empty line and direction filters mean all lines and directions; GTFS `direction_id` values are meaningful only in the context of a particular line, not universally as geographic directions. Use a UTF-8 editor for Polish names.

Launch from an active X11 session as the dedicated user (press Escape on an attached keyboard to exit):

```sh
cd "$HOME/piSchedule"
java -Xms16m -Xmx64m -XX:+UseSerialGC -jar piSchedule.jar --config config.toml
```

These JVM options are **starting experiments**, not validated limits. Ensure the chosen config, cache and temporary locations remain accessible when started from Openbox. Monitor startup and imports; adjust the heap and investigate errors rather than assuming this configuration is sufficient.

### Discover stops

The CLI searches the configured feed without a GUI:

```sh
cd "$HOME/piSchedule"
java -jar piSchedule.jar --config config.toml --search-stops 'Łagiewniki'
```

Inspect the returned IDs, exact names, platform information and any station/parent relationships against the current feed; select every intended tram platform using `transit.stop_ids` if necessary. Search may show similarly named nearby stops; do not automatically include them merely because they contain the same substring.

To download, import and query the configured feed without a GUI (this updates the configured cache), stop any running dashboard using the same configuration and run:

```sh
java -jar piSchedule.jar --config config.toml --check-feed
```

Compare its scheduled departures with an official timetable. An import failure is reported instead of replacing a working cache.

In the tram archive retrieved on 11 November 2026, `stops.txt` contained four exact Łagiewniki records: `stop_314_92219` (01), `stop_314_92229` (02), `stop_314_92249` (04), and `stop_314_92259` (05). They have no `parent_station` or `platform_code`; those labels are in `stop_desc`, with `stop_code` values `553-01`, `553-02`, `553-04`, and `553-05`. Łagiewniki ZUS and Łagiewniki SKA are separate names. IDs can change, so the default uses the exact name rather than hard-coding this snapshot. Recheck after changing feeds or when departures look incomplete. The [GTFS reference](https://gtfs.org/documentation/schedule/reference/#stopstxt) defines stop IDs and station/platform relationships.

## Start automatically in X11/Openbox

This is one minimal **console-autologin** approach for a dedicated Pi, not a locked-down kiosk. It requires the deployed JAR and configuration first. Configure the dedicated user for console autologin using `sudo raspi-config` (System Options → Boot / Auto Login → console autologin; labels may vary). Do not also enable a desktop display manager or another launcher on the same display.

Create the user's configuration directory, then create the files below (preserving any existing settings you need):

```sh
mkdir -p "$HOME/.config/openbox" "$HOME/piSchedule"
```

Create `~/.xinitrc` for that user:

```sh
#!/bin/sh
exec openbox-session
```

Create `~/.config/openbox/autostart`:

```sh
# Apply to this X session; monitor/driver power-saving may need separate settings.
xset s off
xset s noblank
xset -dpms

# Keep paths and logs tied to the dedicated user's home.
(
  cd "$HOME/piSchedule" || exit 1
  exec java -Xms16m -Xmx64m -XX:+UseSerialGC -jar piSchedule.jar --config config.toml
) >> "$HOME/piSchedule/piSchedule.log" 2>&1 &
```

Add this block to the dedicated user's `~/.profile`, after checking existing login scripts so X is not launched twice:

```sh
if [ -z "$DISPLAY" ] && [ "$(tty)" = /dev/tty1 ]; then
  exec startx
fi
```

Ensure the login shell actually reads `~/.profile`; an existing `~/.bash_profile` may take precedence. Reboot and confirm the console login starts X, Openbox and the dashboard full-screen. For a manual test, log into the local console and run `startx` if it has not already started. Inspect `~/piSchedule/piSchedule.log` and the X session log if it fails. This sample does **not** supervise or restart a crashed app, rotate logs, wait for the network, or run a service; configure monitoring/restart and log rotation separately if needed. Automatic console login gives anyone with physical access a user session, and X11 is not a security boundary. Provide a safe recovery route before enabling it.

The `xset` commands disable X11 screen saver and DPMS for this session only; a screen's backlight, driver, firmware, or another power manager may still blank it. Confirm it stays lit after the relevant timeout and configure hardware-specific power management as needed.

## Display and touch

The intended active area is approximately 96 × 55 mm (about 4.4 inches diagonal), but the actual model, pixel resolution and orientation are unknown. Identify the panel and touch controller first; follow the manufacturer's instructions for power, cabling, display driver, resolution and any required overlays. Do not infer pixel resolution from physical size. Install and test the screen independently of piSchedule.

In the X11 session, `xrandr` shows outputs/modes and `xinput list` shows input devices. Configure rotation and any touch calibration/coordinate transformation at the OS or input-driver level using the correct instructions for the actual hardware; changing the display rotation alone may leave touch coordinates misaligned. Check all four corners, center and press/drag/release after every orientation change. Verify a single-finger tap maps to ordinary mouse events; Swing does not promise portable multi-touch. Tune text scale and row count on the actual panel for readable text and contrast. There are no version-one touch navigation controls.

## Data, attribution and operational limits

- The cache is intended to preserve the previous valid database if an update fails, show stale/expired timetable and weather states, and work offline when a usable cache exists; these behaviors still need end-to-end verification. A first start without a valid cache needs network access. Feed errors, outdated schedules, inaccurate clocks and provider outages can make results incomplete; use official sources for consequential travel decisions.
- Queries look ahead at most 31 days and return at most 200 departures. Frequency-based selected trips, flexible service, approximate selected-stop times, continuous pickup, and conflicting agency time zones are unsupported and cause import to fail rather than produce misleading times. An import also fails if the configured filters match none of the selected-stop departures. Calls requiring phone/driver coordination cause import to fail; arrival-only calls are omitted. The cache filesystem must support atomic replacement and file locking.
- Timetable caches are scoped to the feed, stop selection and filters. Changing those settings requires a successful import before the changed configuration can work offline, and old cache files may need manual cleanup. Stop search tries to download the current feed and warns if it falls back to a cached archive.
- Downloads have size, connect, read and body-duration limits, but a slowly trickling HTTP response can exceed the intended total duration; shutdown waits briefly for background cleanup rather than guaranteeing it.
- No on-screen settings, settings web server, authentication, remote administration, process supervision or kiosk hardening are promised. Restrict SSH/network access, keep packages updated, protect configuration and writable directories, and run as an unprivileged user. Treat provider data and configurable URLs as untrusted; use HTTPS and reasonable download limits when implemented. Autologin plus physical access allows local use of the account.

## Measurement and device acceptance

All of the following are **pending**, not claims of completed testing. The aspirational application target is under about 100 MiB resident memory during normal operation, excluding X/Openbox; import memory must also be bounded. Heap limits do not include JVM metadata, thread stacks, native GUI libraries or SQLite allocations, and do not cap RSS.

On the Pi, record image/version, architecture, Java and driver versions, display model/resolution, configured JVM flags and feed date. Measure after startup and after data has settled, and during a full first import and a later refresh. Identify the application PID from `pgrep -af 'piSchedule.jar'`, then substitute it below:

```sh
ps -p <PID> -o pid,rss,vsz,etime,cmd
grep -E 'VmRSS|VmHWM' /proc/<PID>/status
df -h
```

RSS and Linux `VmHWM` are in KiB; `VmHWM` is the process peak so far, so record it after the import and sample RSS during both phases. Record X11/Openbox and overall system memory separately. Observe heap use alongside RSS, for example with a matching JDK's `jcmd <PID> GC.heap_info` run as the same user, if that JDK/tool is installed, or JVM GC logging enabled at launch. Measure startup-to-visible time, import duration and departure query responsiveness, and check free disk space and logging growth. Repeat after tuning; the proposed `-Xmx64m` is not evidence of a <100 MiB process.

Acceptance checklist:

- [ ] Install supported 32-bit GUI OpenJDK; launch the desktop-built dependency-containing JAR and perform SQLite reads/writes on Pi 3 without native loading errors.
- [ ] Confirm reboot-to-full-screen autostart and no X11 or hardware blanking.
- [ ] Confirm actual platforms, all intended directions and scheduled departure results against the current feed; exercise filtered and unfiltered settings.
- [ ] Verify readability on the actual panel and touch corner alignment, tap, press/drag/release and rotation.
- [ ] Confirm a responsive clock and interface during import and network failure; verify Swing updates occur on the event-dispatch thread.
- [ ] Test offline startup with a valid cache, failed updates preserving that cache, and visible stale/expired timetable and weather indications.
- [ ] Record steady and peak import RSS, heap usage, startup/import times and query responsiveness; tune and repeat.
