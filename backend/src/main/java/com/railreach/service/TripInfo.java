package com.railreach.service;

/** What identifies a trip on the operator's own site. A field is null when the feed leaves it out. */
public record TripInfo(String trainNumber, String headsign, String operator) {}
