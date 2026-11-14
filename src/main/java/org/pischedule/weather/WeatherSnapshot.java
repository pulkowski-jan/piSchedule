package org.pischedule.weather;

/** An immutable, display-ready weather reading. An empty status means it is fresh. */
public record WeatherSnapshot(String temperature, String condition, String icon, String status) {
}
