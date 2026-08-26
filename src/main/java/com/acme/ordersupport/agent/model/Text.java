package com.acme.ordersupport.agent.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Date rendering for tool payloads: ISO-8601 in UTC, or null. */
final class Text {

    private static final DateTimeFormatter INSTANT_FORMAT =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    private Text() {
    }

    static String iso(Instant value) {
        return value != null ? INSTANT_FORMAT.format(value) : null;
    }

    static String iso(LocalDate value) {
        return value != null ? value.toString() : null;
    }
}
