package com.dezxxx.individuals.error;

import com.dezxxx.individuals.api.model.ErrorResponse;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;

// Builds our ErrorResponse - the one place, so the two roads to an error
// (GlobalExceptionHandler and the security handlers) give the same body
@Component
public class ErrorResponseFactory {

    // traceId is required by the contract, so there is always a value
    private static final String NO_TRACE = "unavailable";

    private final Tracer tracer;

    public ErrorResponseFactory(Tracer tracer) {
        this.tracer = tracer;
    }

    // message is for the client - never an exception message
    public ErrorResponse create(ErrorCode code, String message, List<String> details, String path) {
        ErrorResponse body = new ErrorResponse()
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .path(path)
                .status(code.getStatus().value())
                .error(code.name())
                .message(message);
        body.setTraceId(currentTraceId());
        // always an array, possibly empty, as in the handout's example
        body.setDetails(details == null ? List.of() : List.copyOf(details));
        return body;
    }

    private String currentTraceId() {
        Span span = tracer.currentSpan();
        return span == null ? NO_TRACE : span.context().traceId();
    }
}
