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

// A fake person-service on a real socket, so the generated PersonsApi,
// WebClient, JSON and timeouts are all exercised. Built on reactor-netty,
// already on the classpath
public final class StubPersonService {

    // must match person-service/openapi/person-service.yaml
    private static final String REGISTRATION_PATH = "/api/v1/persons/registration";

    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    private final UUID userUid = UUID.randomUUID();

    private DisposableServer server;

    // a free port picked by the OS
    public void start() {
        server = HttpServer.create()
                .port(0)
                .route(routes -> routes.post(REGISTRATION_PATH, (request, response) ->
                        request.receive()
                                .aggregate()
                                .asString(StandardCharsets.UTF_8)
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    receivedBodies.add(body);
                                    return response.status(HttpResponseStatus.CREATED)
                                            .header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                                            .sendString(Mono.just("{\"userUid\":\"" + userUid + "\"}"))
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

    // the user_uid this stub always returns, so a test can find it in Keycloak
    public UUID userUid() {
        return userUid;
    }

    // bodies received, in order, so a test can check what we sent
    public List<String> receivedBodies() {
        return List.copyOf(receivedBodies);
    }
}
