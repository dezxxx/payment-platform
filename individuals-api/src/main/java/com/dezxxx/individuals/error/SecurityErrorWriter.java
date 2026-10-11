package com.dezxxx.individuals.error;

import com.dezxxx.individuals.api.model.ErrorResponseDto;
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

        ErrorResponseDto body = errorResponseFactory.create(code, code.getDefaultMessage(), List.of(), path);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JacksonException ex) {
            log.error("Failed to serialise the error response for {}", path, ex);
            return response.setComplete();
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        RequestLog.errorCode(code.name());
        log.warn("{} {} -> {} {}", exchange.getRequest().getMethod(), path, code.getStatus().value(), code);
        // an unwritten buffer must be released by hand, or pooled memory leaks
        return response.writeWith(Mono.just(buffer))
                .doOnError(ex -> DataBufferUtils.release(buffer));
    }
}
