package com.dezxxx.individuals.logging;

import io.micrometer.context.ContextRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

// Two jobs: (1) the constructor teaches Reactor how to carry RequestLog across
// threads into the MDC - once for the whole app; (2) every request gets its own
// RequestLog and one summary line at the end. Runs first, before security,
// so a 401 is logged like any other request
@Slf4j
@Component
public class RequestLogFilter implements WebFilter, Ordered {

    // scrapes and probes come constantly - no summary line for them
    private static final String ACTUATOR_PREFIX = "/actuator";

    public RequestLogFilter() {
        // getter, setter, cleanup - the short form of a ThreadLocalAccessor
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                RequestLog.CONTEXT_KEY,
                RequestLog::current,
                RequestLog::bind,
                RequestLog::unbind);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String method = request.getMethod().name();
        String path = request.getPath().value();
        RequestLog requestLog = new RequestLog(method, path);

        return chain.filter(exchange)
                // doOnEach, not doFinally: here the traceId is restored,
                // so the summary line carries the same traceId as the rest
                .doOnEach(signal -> {
                    if (signal.isOnComplete() || signal.isOnError()) {
                        finish(exchange, requestLog, method, path);
                    }
                })
                .contextWrite(context -> context.put(RequestLog.CONTEXT_KEY, requestLog));
    }

    // the status exists only now, after the response was written
    private static void finish(ServerWebExchange exchange, RequestLog requestLog, String method, String path) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        if (status != null) {
            requestLog.set(RequestLog.STATUS, Integer.toString(status.value()));
        }
        if (!path.startsWith(ACTUATOR_PREFIX)) {
            log.info("{} {} -> {}", method, path, status == null ? "no status" : status.value());
        }
    }
}
