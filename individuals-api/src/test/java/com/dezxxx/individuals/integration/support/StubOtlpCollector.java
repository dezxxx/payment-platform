package com.dezxxx.individuals.integration.support;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Tempo, reduced to the only thing a test can actually check about it.
 *
 * <p>Running Tempo itself in a test would prove something we do not control -
 * that Tempo stores and indexes what it is given. What we do control ends at
 * the socket: the application has to build spans and push them over OTLP to the
 * configured endpoint. This receives that push and remembers it, so the
 * assertion is about our export rather than about someone else's database.
 *
 * <p>It answers <b>200 (OK)</b> with an empty body, which is a valid empty
 * {@code ExportTraceServiceResponse} - the OTLP exporter accepts it and does
 * not retry.
 */
public final class StubOtlpCollector {

    /** The path the OTLP/HTTP exporter posts traces to. */
    private static final String TRACES_PATH = "/v1/traces";

    private final List<Integer> exportedPayloadSizes = new CopyOnWriteArrayList<>();

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

    /**
     * Size in bytes of every export received. A count says traces were pushed;
     * a non-zero size says they carried spans rather than an empty batch.
     */
    public List<Integer> exportedPayloadSizes() {
        return List.copyOf(exportedPayloadSizes);
    }
}
