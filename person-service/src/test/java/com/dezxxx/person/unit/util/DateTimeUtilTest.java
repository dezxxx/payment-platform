package com.dezxxx.person.unit.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.person.util.DateTimeUtil;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DateTimeUtil")
class DateTimeUtilTest {

    @Test
    @DisplayName("given an instant, when converted, then it is the same moment shown in UTC")
    void showsInUtc() {
        // given
        Instant instant = Instant.parse("2026-06-08T10:15:30Z");

        // when
        OffsetDateTime result = DateTimeUtil.toUtc(instant);

        // then
        assertThat(result).isEqualTo(OffsetDateTime.of(2026, 6, 8, 10, 15, 30, 0, ZoneOffset.UTC));
        assertThat(result.getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("given no time, when converted, then null - verifiedAt and archivedAt are empty for a new user")
    void keepsNull() {
        // when / then
        assertThat(DateTimeUtil.toUtc(null)).isNull();
    }
}
