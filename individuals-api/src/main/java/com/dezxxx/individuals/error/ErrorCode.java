package com.dezxxx.individuals.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Stable machine-readable error codes of the external API.
 *
 * <p>Every constant owns both the HTTP status it travels with and the message
 * the client is given. The status line, the {@code status} field and the
 * {@code error} field of the response are therefore all derived from a single
 * line here and cannot drift apart.
 *
 * <p>The codes and the default messages match the examples in
 * {@code openapi/individuals-api.yaml}.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),

    /**
     * Deliberately says "email or password" rather than naming which one was
     * wrong: a different answer per case would let an attacker discover which
     * addresses are registered.
     */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email or password is incorrect"),

    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access is denied"),

    USER_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email is already registered"),

    /**
     * The next three are not declared per operation in the contract. They are
     * not business outcomes but protocol-level failures the framework raises
     * before any operation is reached, and every HTTP API can produce them.
     * Declaring them here keeps such answers in the contract's error shape
     * instead of letting them fall through to a misleading 500.
     */
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported for this path"),

    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type is not supported"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"),

    /**
     * Registration wrote to person-service and then failed in Keycloak, so the
     * two systems now disagree about whether this person exists.
     *
     * <p>Deliberately not {@code INTERNAL_ERROR}. Registration writes to two
     * systems with no transaction spanning them, and a half-finished one must
     * be findable: this code is what a log query, an alert and a metric filter
     * on. Folding it into the generic 500 would hide exactly the failure that
     * needs a human.
     *
     * <p>The message says "contact support" rather than describing the split.
     * The caller cannot repair it - the address is now taken in one system and
     * unknown in the other - and naming the internal state would leak our
     * topology.
     */
    REGISTRATION_INCONSISTENT(HttpStatus.INTERNAL_SERVER_ERROR,
            "Registration did not complete, please contact support"),

    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dependency is not reachable");

    private final HttpStatus status;

    private final String defaultMessage;
}
