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

/**
 * person-service, as far as these tests are concerned.
 *
 * <p>The real one is module 2 and ships no code yet, so there is no container
 * to start. A stub is the honest substitute: registration cannot be exercised
 * end to end without something answering on the first step of the scenario.
 *
 * <p>A real socket rather than a mocked bean, because the point of an
 * integration test is that the wire is part of what is tested - the generated
 * {@code PersonsApi} proxy, the WebClient underneath it, JSON serialisation and
 * the timeouts all have to work. Built on reactor-netty, which is already on
 * the classpath as WebFlux's own server, so this costs no new dependency.
 */
public final class StubPersonService {

    /** Must match the path in {@code person-service/openapi/person-service.yaml}. */
    private static final String REGISTRATION_PATH = "/api/v1/persons/registration";

    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    private final UUID userUid = UUID.randomUUID();

    private DisposableServer server;

    /** Binds on a free port chosen by the OS, so parallel runs never collide. */
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

    /**
     * The identifier this stub hands out. Fixed for the whole run, so a test
     * can assert that exactly this value reached Keycloak as {@code user_uid}.
     */
    public UUID userUid() {
        return userUid;
    }

    /** Raw bodies received, in order, so a test can assert what we sent. */
    public List<String> receivedBodies() {
        return List.copyOf(receivedBodies);
    }
}
