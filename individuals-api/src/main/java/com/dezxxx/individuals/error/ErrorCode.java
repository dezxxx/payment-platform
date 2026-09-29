package com.dezxxx.individuals.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// Our error codes. Each one carries its HTTP status and client message, so the
// three can never drift apart. Must match openapi/individuals-api.yaml.
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),

    // wrong password at login. Never says which of the two was wrong,
    // or anyone could find out which emails are registered
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email or password is incorrect"),

    // no usable token: missing, expired, foreign - or an unknown path,
    // since everything outside the public list needs a token first
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "A valid access token is required"),

    // refresh token is dead - the session ended, show the login form.
    // Keycloak says invalid_grant for this and for a wrong password alike;
    // TokenService knows which call it was and picks this code
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Refresh token is expired or no longer valid"),

    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access is denied"),

    USER_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email is already registered"),

    // the next three come from the framework, not from our operations;
    // listed so they keep our error shape instead of turning into a 500
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported for this path"),

    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type is not supported"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"),

    // person-service has the person, Keycloak has no account: the two disagree.
    // Own code so logs and alerts can find it. 503 as the handout requires
    // (UT-REG-004); the message does not describe our internals
    REGISTRATION_INCONSISTENT(HttpStatus.SERVICE_UNAVAILABLE,
            "Registration did not complete, please contact support"),

    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dependency is not reachable");

    private final HttpStatus status;

    private final String defaultMessage;
}
