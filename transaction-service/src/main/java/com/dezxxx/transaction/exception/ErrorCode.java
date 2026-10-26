package com.dezxxx.transaction.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),

    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request body cannot be read"),

    WALLET_TYPE_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "The wallet type is archived or not active"),

    WALLET_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "The wallet is not active"),

    INSUFFICIENT_FUNDS(HttpStatus.BAD_REQUEST, "Not enough funds including the fee"),

    CURRENCY_MISMATCH(HttpStatus.BAD_REQUEST, "The wallets have different currencies"),

    WALLET_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND, "Wallet type not found"),

    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "Wallet not found"),

    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Transaction not found"),

    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),

    REQUEST_ID_CONFLICT(HttpStatus.CONFLICT, "requestId already used for another operation"),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported for this path"),

    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type is not supported"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    private final HttpStatus status;

    private final String defaultMessage;
}
