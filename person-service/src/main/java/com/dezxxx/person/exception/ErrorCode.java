package com.dezxxx.person.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// Our error codes. Each one carries its HTTP status and client message, so the
// two can never drift apart. The name goes to the client as the "error" field.
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // a field breaks the contract: missing, too long, not an email, not a UUID
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),

    // the body is not JSON at all, or a field has the wrong type
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request body cannot be read"),

    // a country code that is not in the countries reference table
    COUNTRY_NOT_FOUND(HttpStatus.BAD_REQUEST, "Unknown country code"),

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),

    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "A user with this email already exists"),

    // optimistic locking: the version read is no longer the version in the database
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The user was changed by another request, reload it and try again"),

    // the next three come from the framework, not from our operations;
    // listed so they keep our error shape
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported for this path"),

    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type is not supported"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    private final HttpStatus status;

    private final String defaultMessage;
}
