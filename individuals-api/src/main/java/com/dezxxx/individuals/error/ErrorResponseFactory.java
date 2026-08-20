package com.dezxxx.individuals.error;

import com.dezxxx.individuals.api.model.ErrorResponse;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds the contract's {@link ErrorResponse}.
 *
 * <p>On the reactive stack a failure is reported from two different places: the
 * advice, for anything a handler raises, and the security handlers, for what
 * the filter chain rejects before a handler is ever chosen. WebFlux has no
 * equivalent of the servlet {@code handlerExceptionResolver}, so the second
 * path cannot be delegated into the first. This class is what keeps them from
 * drifting apart - the body is assembled here and nowhere else.
 */
@Component
public class ErrorResponseFactory {

    /**
     * Returned as the trace id when there is no active span - the field is
     * required by the contract, so it always carries a value.
     */
    private static final String NO_TRACE = "unavailable";

    private final Tracer tracer;

    public ErrorResponseFactory(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * @param code    business code; carries the HTTP status as well
     * @param message text meant for the client, never an exception message
     * @param details field-level failures, may be empty
     * @param path    request path, taken from the exchange
     */
    public ErrorResponse create(ErrorCode code, String message, List<String> details, String path) {
        ErrorResponse body = new ErrorResponse()
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .path(path)
                .status(code.getStatus().value())
                .error(code.name())
                .message(message);
        body.setTraceId(currentTraceId());
        // The generated model starts with an empty list; null keeps the field
        // out of the response entirely when there is nothing to report.
        body.setDetails(details == null || details.isEmpty() ? null : List.copyOf(details));
        return body;
    }

    private String currentTraceId() {
        Span span = tracer.currentSpan();
        return span == null ? NO_TRACE : span.context().traceId();
    }
}
