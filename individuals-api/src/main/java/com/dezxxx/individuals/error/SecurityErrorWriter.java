package com.dezxxx.individuals.error;

import com.dezxxx.individuals.api.model.ErrorResponse;
import com.dezxxx.individuals.logging.RequestLog;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Writes an {@link ErrorResponse} straight into the exchange.
 *
 * <p>Used by the two security handlers. Their failures happen inside the filter
 * chain, so no {@code @ExceptionHandler} will ever run for them and there is no
 * message converter in play either - the body has to be serialised and written
 * by hand.
 *
 * <p>The {@link ObjectMapper} is the one Spring Boot configured, so
 * {@code spring.jackson.default-property-inclusion} applies here exactly as it
 * does to a response produced by a controller.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityErrorWriter {

    private final ErrorResponseFactory errorResponseFactory;

    private final ObjectMapper objectMapper;

    public Mono<Void> write(ServerWebExchange exchange, ErrorCode code) {
        ServerHttpResponse response = exchange.getResponse();
        String path = exchange.getRequest().getPath().value();

        response.setStatusCode(code.getStatus());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        ErrorResponse body = errorResponseFactory.create(code, code.getDefaultMessage(), List.of(), path);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JacksonException ex) {
            // Serialising our own model cannot realistically fail. If it ever
            // does, the status is already set - close the exchange rather than
            // fail the response with a second error.
            log.error("Failed to serialise the error response for {}", path, ex);
            return response.setComplete();
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        // The other road to an error answer, and it has to record the business
        // code just as GlobalExceptionHandler does - a rejection from the
        // filter chain never reaches an advice.
        RequestLog.errorCode(code.name());
        log.warn("{} {} -> {} {}", exchange.getRequest().getMethod(), path, code.getStatus().value(), code);
        // A buffer that is never written has to be released by hand, or the
        // pooled memory behind it leaks.
        return response.writeWith(Mono.just(buffer))
                .doOnError(ex -> DataBufferUtils.release(buffer));
    }
}
