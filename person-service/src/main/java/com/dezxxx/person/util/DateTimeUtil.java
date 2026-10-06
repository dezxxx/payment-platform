package com.dezxxx.person.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class DateTimeUtil {

    private DateTimeUtil() {
    }

    // an Instant is a point in time with no zone; the JSON shows it in UTC, with the Z
    public static OffsetDateTime toUtc(Instant time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }
}
