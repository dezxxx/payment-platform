package com.dezxxx.individuals.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.HttpServer;

// keeps the last created individual's user, so /me can read it back and PATCH can change it
public final class StubPersonService {

    private static final String INDIVIDUALS_PATH = "/api/v1/individuals";

    private static final String INDIVIDUAL_PATH = "/api/v1/individuals/{id}";

    private static final String COMPENSATION_PATH = "/api/v1/individuals/compensate-registration/{id}";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    private final AtomicReference<ObjectNode> user = new AtomicReference<>(JSON.createObjectNode());

    private final UUID userUid = UUID.randomUUID();

    private DisposableServer server;

    public void start() {
        server = HttpServer.create()
                .port(0)
                .route(routes -> routes
                        .delete(COMPENSATION_PATH, (request, response) -> response.status(HttpResponseStatus.NO_CONTENT).send())
                        .post(INDIVIDUALS_PATH, (request, response) -> bodyOf(request).flatMap(body -> {
                            receivedBodies.add(body);
                            user.set((ObjectNode) read(body).path("user").deepCopy());
                            return sendIndividual(response, HttpResponseStatus.CREATED);
                        }))
                        .get(INDIVIDUAL_PATH, (request, response) -> sendIndividual(response, HttpResponseStatus.OK))
                        .route(request -> request.method().equals(HttpMethod.PATCH)
                                        && request.uri().startsWith(INDIVIDUALS_PATH + "/"),
                                (request, response) -> bodyOf(request).flatMap(body -> {
                            JsonNode changes = read(body).path("user");
                            user.updateAndGet(current -> {
                                ObjectNode next = current.deepCopy();
                                changes.properties().forEach(field -> next.set(field.getKey(), field.getValue()));
                                return next;
                            });
                            return sendIndividual(response, HttpResponseStatus.OK);
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

    private static Mono<String> bodyOf(HttpServerRequest request) {
        return request.receive().aggregate().asString(StandardCharsets.UTF_8).defaultIfEmpty("{}");
    }

    private Mono<Void> sendIndividual(HttpServerResponse response, HttpResponseStatus status) {
        ObjectNode individual = JSON.createObjectNode();
        individual.put("id", userUid.toString());
        individual.set("user", user.get());
        return response.status(status)
                .header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .sendString(Mono.just(individual.toString()))
                .then();
    }

    private static JsonNode read(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Not JSON: " + body, ex);
        }
    }
}
