package com.dezxxx.individuals.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),

    // never says which of the two was wrong, or anyone could probe registered emails
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email or password is incorrect"),

    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "A valid access token is required"),

    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Refresh token is expired or no longer valid"),

    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access is denied"),

    USER_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email is already registered"),

    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),

    // refusals of transaction-service, passed to the client with its own code and text
    INSUFFICIENT_FUNDS(HttpStatus.BAD_REQUEST, "Not enough funds including the fee"),

    CURRENCY_MISMATCH(HttpStatus.BAD_REQUEST, "The wallets have different currencies"),

    WALLET_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "The wallet is not active"),

    WALLET_TYPE_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "The wallet type is archived or not active"),

    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "Wallet not found"),

    WALLET_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND, "Wallet type not found"),

    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Transaction not found"),

    REQUEST_ID_CONFLICT(HttpStatus.CONFLICT, "requestId already used for another operation"),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported for this path"),

    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type is not supported"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"),

    REGISTRATION_INCONSISTENT(HttpStatus.SERVICE_UNAVAILABLE,
            "Registration did not complete, please contact support"),

    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dependency is not reachable");

    private final HttpStatus status;

    private final String defaultMessage;
}
