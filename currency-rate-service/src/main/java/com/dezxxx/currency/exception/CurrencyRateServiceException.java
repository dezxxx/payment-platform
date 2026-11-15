package com.dezxxx.currency.exception;

import java.io.Serial;
import lombok.Getter;

@Getter
public class CurrencyRateServiceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public CurrencyRateServiceException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage());
    }

    public CurrencyRateServiceException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
