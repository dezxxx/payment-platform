package com.dezxxx.transaction.unit.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import com.dezxxx.transaction.validation.DateRangeValidator;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DateRangeValidator")
class DateRangeValidatorTest {

    private static final OffsetDateTime EARLY = OffsetDateTime.parse("2026-10-01T00:00:00Z");

    private static final OffsetDateTime LATE = OffsetDateTime.parse("2026-10-31T00:00:00Z");

    private final DateRangeValidator validator = new DateRangeValidator();

    @Test
    @DisplayName("given dateFrom after dateTo, when checked, then VALIDATION_ERROR naming both")
    void refusesReversedRange() {
        // when / then
        assertThatThrownBy(() -> validator.requireOrdered(LATE, EARLY))
                .isInstanceOf(TransactionServiceException.class)
                .hasMessage("dateFrom must not be after dateTo")
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("given dateFrom before or equal to dateTo, when checked, then it passes")
    void acceptsOrderedRange() {
        // when / then
        assertThatCode(() -> validator.requireOrdered(EARLY, LATE)).doesNotThrowAnyException();
        assertThatCode(() -> validator.requireOrdered(EARLY, EARLY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("given only one date or none, when checked, then it passes: the range is open on that side")
    void acceptsOpenRange() {
        // when / then
        assertThatCode(() -> validator.requireOrdered(EARLY, null)).doesNotThrowAnyException();
        assertThatCode(() -> validator.requireOrdered(null, LATE)).doesNotThrowAnyException();
        assertThatCode(() -> validator.requireOrdered(null, null)).doesNotThrowAnyException();
    }
}
