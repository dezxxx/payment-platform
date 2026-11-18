package com.dezxxx.individuals.integration.support;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public final class StubCurrencyRateService {

    public record Received(String uri, String authorization) {
    }

    private record Failure(int status, String error) {
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();

    private final Map<String, BigDecimal> rates = new ConcurrentHashMap<>();

    private final AtomicReference<Failure> failure = new AtomicReference<>();

    private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);

    private DisposableServer server;

    public void start() {
        server = HttpServer.create()
                .port(0)
                .handle((request, response) -> {
                    received.add(new Received(request.uri(), request.requestHeaders().get(HttpHeaderNames.AUTHORIZATION)));
                    return Mono.delay(delay.get()).then(Mono.defer(() -> answer(request, response)));
                })
                .bindNow();
    }

    public String baseUrl() {
        return "http://localhost:" + server.port();
    }

    public void reset() {
        received.clear();
        rates.clear();
        failure.set(null);
        delay.set(Duration.ZERO);
    }

    public void answerRate(String from, String to, String rate) {
        rates.put(from + to, new BigDecimal(rate));
    }

    public void answerProblem(int status, String error) {
        failure.set(new Failure(status, error));
    }

    public void answerLate(Duration answerDelay) {
        delay.set(answerDelay);
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    // a pair without a rate gets 404 RATE_NOT_FOUND, as the real service answers it
    private Mono<Void> answer(HttpServerRequest request, HttpServerResponse response) {
        Failure next = failure.get();
        if (next != null) {
            return problem(response, next.status(), next.error());
        }
        Map<String, List<String>> query = new QueryStringDecoder(request.uri()).parameters();
        String from = query.get("from").getFirst();
        String to = query.get("to").getFirst();
        BigDecimal rate = rates.get(from + to);
        if (rate == null) {
            return problem(response, 404, "RATE_NOT_FOUND");
        }
        return response.status(HttpResponseStatus.OK)
                .header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .sendString(Mono.just("""
                        {"sourceCode":"%s","destinationCode":"%s","rate":%s,
                         "rateTimestamp":"2026-11-14T10:00:00Z","providerCode":"OXR"}
                        """.formatted(from, to, rate.toPlainString())))
                .then();
    }

    private static Mono<Void> problem(HttpServerResponse response, int status, String error) {
        return response.status(HttpResponseStatus.valueOf(status))
                .header(HttpHeaderNames.CONTENT_TYPE, "application/problem+json")
                .sendString(Mono.just("{\"status\":" + status + ",\"error\":\"" + error + "\",\"details\":[]}"))
                .then();
    }
}
