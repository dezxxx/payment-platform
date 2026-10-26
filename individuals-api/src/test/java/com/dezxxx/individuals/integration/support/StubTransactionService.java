package com.dezxxx.individuals.integration.support;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;

// answers every request with what the test set, and keeps what individuals-api sent
public final class StubTransactionService {

    public record Received(String method, String uri, String userUid, String body) {
    }

    private record Answer(int status, String contentType, String body, Duration delay) {
    }

    private static final Answer EMPTY_LIST = new Answer(200, "application/json", "[]", Duration.ZERO);

    private final List<Received> received = new CopyOnWriteArrayList<>();

    private final AtomicReference<Answer> answer = new AtomicReference<>(EMPTY_LIST);

    private DisposableServer server;

    public void start() {
        server = HttpServer.create()
                .port(0)
                .handle((request, response) -> bodyOf(request).flatMap(body -> {
                    received.add(new Received(request.method().name(), request.uri(),
                            request.requestHeaders().get("X-User-Uid"), body));
                    Answer next = answer.get();
                    return Mono.delay(next.delay())
                            .then(response.status(HttpResponseStatus.valueOf(next.status()))
                                    .header(HttpHeaderNames.CONTENT_TYPE, next.contentType())
                                    .sendString(Mono.just(next.body()))
                                    .then());
                }))
                .bindNow();
    }

    public String baseUrl() {
        return "http://localhost:" + server.port();
    }

    public void reset() {
        received.clear();
        answer.set(EMPTY_LIST);
    }

    public void answer(int status, String json) {
        answer.set(new Answer(status, "application/json", json, Duration.ZERO));
    }

    public void answerProblem(int status, String error, String details) {
        answer.set(new Answer(status, "application/problem+json",
                "{\"status\":" + status + ",\"error\":\"" + error + "\",\"details\":" + details + "}", Duration.ZERO));
    }

    public void answerLate(Duration delay) {
        answer.set(new Answer(200, "application/json", "[]", delay));
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    private static Mono<String> bodyOf(HttpServerRequest request) {
        return request.receive().aggregate().asString(StandardCharsets.UTF_8).defaultIfEmpty("");
    }
}
