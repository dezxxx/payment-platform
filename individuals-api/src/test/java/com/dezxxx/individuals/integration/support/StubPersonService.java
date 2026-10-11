package com.dezxxx.individuals.integration.support;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

public final class StubPersonService {

    private static final String USERS_PATH = "/api/v1/users";

    private static final String USER_PATH = "/api/v1/users/{id}";

    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    private final UUID userUid = UUID.randomUUID();

    private DisposableServer server;

    public void start() {
        server = HttpServer.create()
                .port(0)
                .route(routes -> routes
                        .delete(USER_PATH, (request, response) -> response.status(HttpResponseStatus.NO_CONTENT).send())
                        .post(USERS_PATH, (request, response) ->
                            request.receive()
                                    .aggregate()
                                    .asString(StandardCharsets.UTF_8)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> {
                                        receivedBodies.add(body);
                                        return response.status(HttpResponseStatus.CREATED)
                                                .header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                                                .sendString(Mono.just("{\"id\":\"" + userUid + "\"}"))
                                                .then();
                                    })))
                .bindNow();
    }

    public void stop() {
        if (server != null) {
            server.disposeNow();
        }
    }

    public String baseUrl() {
        return "http://localhost:" + server.port();
    }

    public UUID userUid() {
        return userUid;
    }

    public List<String> receivedBodies() {
        return List.copyOf(receivedBodies);
    }
}
