package com.dezxxx.individuals.error;

import java.io.Serial;
import java.util.List;
import lombok.Getter;

// Every failure we report to the client. One class: the case is in the ErrorCode
@Getter
public class ApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    // field-level failures, one line each; never null
    private final transient List<String> details;

    // uses the code's own message - the common case
    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), List.of(), null);
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of(), null);
    }

    // wraps a failure from another system; the cause keeps its stack trace
    public ApiException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, List.of(), cause);
    }

    public ApiException(ErrorCode errorCode, String message, List<String> details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details == null ? List.of() : List.copyOf(details);
    }
}
