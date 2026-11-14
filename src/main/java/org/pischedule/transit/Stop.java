package org.pischedule.transit;

/** A GTFS stop or station, with empty strings for absent optional fields. */
public record Stop(String id, String name, String parentId, String platform) {}
