package com.dezxxx.person.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),

    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request body cannot be read"),

    COUNTRY_NOT_FOUND(HttpStatus.BAD_REQUEST, "Unknown country code"),

    INDIVIDUAL_NOT_FOUND(HttpStatus.NOT_FOUND, "Individual not found"),

    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "An individual with this email already exists"),

    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The individual was changed by another request, reload it and try again"),

    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported for this path"),

    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type is not supported"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    private final HttpStatus status;

    private final String defaultMessage;
}
