package com.dezxxx.individuals.error;

import com.dezxxx.individuals.api.model.ErrorResponseDto;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ErrorResponseFactory {

    private static final String NO_TRACE = "unavailable";

    private final Tracer tracer;

    public ErrorResponseFactory(Tracer tracer) {
        this.tracer = tracer;
    }

    public ErrorResponseDto create(ErrorCode code, String message, List<String> details, String path) {
        ErrorResponseDto body = new ErrorResponseDto()
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .path(path)
                .status(code.getStatus().value())
                .error(code.name())
                .message(message);
        body.setTraceId(currentTraceId());
        body.setDetails(details == null ? List.of() : List.copyOf(details));
        return body;
    }

    private String currentTraceId() {
        Span span = tracer.currentSpan();
        return span == null ? NO_TRACE : span.context().traceId();
    }
}
