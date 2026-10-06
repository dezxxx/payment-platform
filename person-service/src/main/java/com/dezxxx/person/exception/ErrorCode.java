package com.dezxxx.person.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// Each case carries its HTTP status and message, so the two can never drift apart
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "A user with this email already exists"),
    // a country code that is not in the countries reference table
    COUNTRY_NOT_FOUND(HttpStatus.BAD_REQUEST, "Unknown country code");

    private final HttpStatus status;
    private final String defaultMessage;
}
