package com.dezxxx.currency.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),

    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "A valid service token is required"),

    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Only a platform service may call this service"),

    RATE_NOT_FOUND(HttpStatus.NOT_FOUND, "No rate of the pair valid at that moment"),

    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported for this path"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    private final HttpStatus status;

    private final String defaultMessage;
}
