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

/**
 * Opens a {@link RequestLog} for every request and closes it with one summary
 * line.
 *
 * <p>Two jobs, and they are easy to confuse:
 *
 * <ol>
 *   <li><b>The bridge.</b> The constructor teaches Reactor's context
 *       propagation how to move a {@code RequestLog} between the Reactor
 *       Context and a thread-local - which is what puts the fields into the MDC
 *       on whichever thread ends up writing a record. Registered once, for the
 *       whole application.
 *   <li><b>The request.</b> Every exchange gets its own {@code RequestLog},
 *       written into the Reactor Context so the whole chain below inherits it.
 * </ol>
 *
 * <p>Ordered first, ahead of the security filter chain, so a request rejected
 * with <b>401 (Unauthorized)</b> is described exactly like one that succeeded.
 * An unauthenticated caller is precisely the case worth being able to read
 * afterwards.
 */
@Slf4j
@Component
public class RequestLogFilter implements WebFilter, Ordered {

    /**
     * Prometheus scrapes every fifteen seconds and the probes run more often
     * still. Their records keep the request fields, but a summary line for each
     * would bury the traffic that matters.
     */
    private static final String ACTUATOR_PREFIX = "/actuator";

    public RequestLogFilter() {
        // The shortest form of a ThreadLocalAccessor: a getter, a setter and a
        // cleanup, rather than a class implementing the interface.
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
                // doOnEach rather than doFinally: its callback is a signal
                // delivery, so context propagation restores the trace and this
                // request's fields around it, and the summary below is written
                // with the same traceId as everything that led to it.
                .doOnEach(signal -> {
                    if (signal.isOnComplete() || signal.isOnError()) {
                        finish(exchange, requestLog, method, path);
                    }
                })
                .contextWrite(context -> context.put(RequestLog.CONTEXT_KEY, requestLog));
    }

    /**
     * The status is set on the response only once the chain has written it,
     * which is why it is recorded here and not at the start. It is written
     * straight into this request's own object rather than through the static
     * entry point, so the summary carries it even if the terminal signal is
     * delivered on a thread the propagation never touched.
     */
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
