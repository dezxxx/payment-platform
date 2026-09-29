package com.dezxxx.individuals.integration.support;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

// Stands in for Tempo: receives the OTLP push and remembers it. Answers 200
// with an empty body - a valid empty answer, so the exporter does not retry
public final class StubOtlpCollector {

    // where the OTLP/HTTP exporter posts traces
    private static final String TRACES_PATH = "/v1/traces";

    private final List<Integer> exportedPayloadSizes = new CopyOnWriteArrayList<>();

    // raw payloads; span names are plain UTF-8 strings inside the protobuf
    private final List<String> exportedPayloads = new CopyOnWriteArrayList<>();

    private DisposableServer server;

    public void start() {
        server = HttpServer.create()
                .port(0)
                .route(routes -> routes.post(TRACES_PATH, (request, response) ->
                        request.receive()
                                .aggregate()
                                .asByteArray()
                                .defaultIfEmpty(new byte[0])
                                .flatMap(payload -> {
                                    exportedPayloadSizes.add(payload.length);
                                    exportedPayloads.add(new String(payload, StandardCharsets.ISO_8859_1));
                                    return response.status(HttpResponseStatus.OK)
                                            .header(HttpHeaderNames.CONTENT_TYPE, "application/x-protobuf")
                                            .send()
                                            .then();
                                })))
                .bindNow();
    }

    public void stop() {
        if (server != null) {
            server.disposeNow();
        }
    }

    public String tracesEndpoint() {
        return "http://localhost:" + server.port() + TRACES_PATH;
    }

    // size of each export: non-zero means it carried spans, not an empty batch
    public List<Integer> exportedPayloadSizes() {
        return List.copyOf(exportedPayloadSizes);
    }

    // true once any export carried a span with this name
    public boolean receivedSpan(String name) {
        return exportedPayloads.stream().anyMatch(payload -> payload.contains(name));
    }
}
