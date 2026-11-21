package com.dezxxx.currency.integration.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

// Open Exchange Rates' place in the tests: a real HTTP server from the JDK, answers queued by the test
public class OxrStub {

    // a plain answer says the rates were published this long ago
    public static final Duration AGE = Duration.ofMinutes(10);

    // the rates of Open Exchange Rates on 2026-11-14
    private static final String RATES = """
            {"EUR": 0.90715, "ILS": 3.216745, "UAH": 45.477786, "USD": 1}""";

    // a null body is filled with RATES published AGE before the answer
    private static final Answer OK = new Answer(200, null, Duration.ZERO);

    private final Queue<Answer> answers = new ConcurrentLinkedQueue<>();

    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private HttpServer server;

    public record Request(String authorization, String query) {
    }

    private record Answer(int status, String body, Duration bodyDelay) {
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.createContext("/api/latest.json", this::answer);
        // a slow answer must not hold back the retry that follows it
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/api";
    }

    public static String body(Instant publishedAt, String rates) {
        return """
                {"disclaimer": "Usage subject to terms", "timestamp": %d, "base": "USD", "rates": %s}"""
                .formatted(publishedAt.getEpochSecond(), rates);
    }

    // without a queued answer the stub gives RATES published AGE ago
    public void willAnswer(int status, String body) {
        answers.add(new Answer(status, body, Duration.ZERO));
    }

    // the headers come at once, the body only after the delay
    public void willAnswerSlowly(Duration bodyDelay) {
        answers.add(new Answer(200, null, bodyDelay));
    }

    public void willAnswerRatesPublishedAt(Instant publishedAt) {
        answers.add(new Answer(200, body(publishedAt, RATES), Duration.ZERO));
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        answers.clear();
        requests.clear();
    }

    private void answer(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        requests.add(new Request(exchange.getRequestHeaders().getFirst("Authorization"),
                query == null ? "" : URLDecoder.decode(query, StandardCharsets.UTF_8)));
        Answer next = answers.poll();
        Answer answer = next == null ? OK : next;
        String text = answer.body() == null ? body(Instant.now().minus(AGE), RATES) : answer.body();
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            pause(answer.bodyDelay());
            out.write(body);
        } catch (IOException ex) {
            // the client gave up waiting and closed the connection
        }
    }

    private static void pause(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
