package com.dezxxx.individuals.error;

import java.io.Serial;
import java.util.List;
import lombok.Getter;

/**
 * Every failure this service reports to the client.
 *
 * <p>One class covers all cases because the case is carried by the
 * {@link ErrorCode}, not by the type. A subclass is only worth adding when
 * some code needs to catch that single case and no other.
 */
@Getter
public class ApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    /** Field-level failures, one readable line each. Never null. */
    private final transient List<String> details;

    /** Uses the message the code carries. The common case. */
    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), List.of(), null);
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of(), null);
    }

    /** Wraps a failure from an external system. The cause carries its stack trace. */
    public ApiException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, List.of(), cause);
    }

    public ApiException(ErrorCode errorCode, String message, List<String> details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details == null ? List.of() : List.copyOf(details);
    }
}
