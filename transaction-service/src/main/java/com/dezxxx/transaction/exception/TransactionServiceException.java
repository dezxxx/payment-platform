package com.dezxxx.transaction.exception;

import java.io.Serial;
import lombok.Getter;

// not TransactionException: Spring already has one in org.springframework.transaction
@Getter
public class TransactionServiceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public TransactionServiceException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage());
    }

    public TransactionServiceException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
