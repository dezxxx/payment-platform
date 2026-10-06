package com.dezxxx.person.exception;

import java.io.Serial;
import lombok.Getter;

// Every failure person-service reports on purpose. One class: the case is in the ErrorCode
@Getter
public class PersonException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public PersonException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage());
    }

    public PersonException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
