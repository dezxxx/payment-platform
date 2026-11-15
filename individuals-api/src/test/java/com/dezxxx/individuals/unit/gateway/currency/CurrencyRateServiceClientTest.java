package com.dezxxx.individuals.unit.gateway.currency;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.currency.client.api.CurrencyRateApi;
import com.dezxxx.individuals.config.CurrencyRateServiceProperties;
import com.dezxxx.individuals.config.DependencyFailurePredicate;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.currency.CurrencyRateServiceClient;
import com.dezxxx.individuals.metrics.AuthMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("CurrencyRateServiceClient")
class CurrencyRateServiceClientTest {

    private static final CurrencyRateServiceProperties PROPERTIES =
            new CurrencyRateServiceProperties("http://currency-rate-service", Duration.ofSeconds(5));

    private static final String BREAKER = "currency-rate-service";

    private static final String RATE_JSON = """
            {"sourceCode":"UAH","destinationCode":"EUR","rate":0.01994710,
             "rateTimestamp":"2026-11-14T10:00:00Z","providerCode":"OXR"}
            """;

    private static final String PROBLEM_JSON = """
            {"status":404,"error":"RATE_NOT_FOUND","details":[]}
            """;

    @Test
    @DisplayName("given a valid rate, when asking UAH -> EUR, then GET /api/v1/rates carries the pair and the rate comes back")
    void readsRate() {
        // given
        AtomicReference<ClientRequest> sent = new AtomicReference<>();
        CurrencyRateServiceClient client = clientAnswering(sent, json(HttpStatus.OK, RATE_JSON));

        // when / then
        StepVerifier.create(client.getRate("UAH", "EUR"))
                .assertNext(rate -> assertThat(rate).isEqualByComparingTo(new BigDecimal("0.01994710")))
                .verifyComplete();

        // then
        assertThat(sent.get().method()).isEqualTo(HttpMethod.GET);
        assertThat(sent.get().url().getPath()).isEqualTo("/api/v1/rates");
        assertThat(sent.get().url().getQuery()).contains("from=UAH").contains("to=EUR").doesNotContain("timestamp");
    }

    @Test
    @DisplayName("given no valid rate, when asking, then RATE_NOT_FOUND")
    void rateNotFound() {
        // given
        CurrencyRateServiceClient client = clientAnswering(new AtomicReference<>(), json(HttpStatus.NOT_FOUND, PROBLEM_JSON));

        // when / then
        expectError(client.getRate("UAH", "EUR"), ErrorCode.RATE_NOT_FOUND);
    }

    @Test
    @DisplayName("given currency-rate-service refuses our request with 400, when asking, then INTERNAL_ERROR - we sent it")
    void badRequestIsOurBug() {
        // given
        CurrencyRateServiceClient client = clientAnswering(new AtomicReference<>(), json(HttpStatus.BAD_REQUEST,
                "{\"status\":400,\"error\":\"VALIDATION_ERROR\",\"details\":[\"from: must match\"]}"));

        // when / then
        expectError(client.getRate("uah", "EUR"), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("given currency-rate-service fails, when asking, then DEPENDENCY_UNAVAILABLE")
    void serverError() {
        // given
        CurrencyRateServiceClient client = clientAnswering(new AtomicReference<>(),
                ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());

        // when / then
        expectError(client.getRate("UAH", "EUR"), ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    @DisplayName("given currency-rate-service is unreachable, when asking, then DEPENDENCY_UNAVAILABLE")
    void unreachable() {
        // given
        CurrencyRateServiceClient client = client(request -> Mono.error(new ConnectException("Connection refused")),
                CircuitBreakerRegistry.ofDefaults());

        // when / then
        expectError(client.getRate("UAH", "EUR"), ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    @DisplayName("given a 200 without a body, when asking, then INTERNAL_ERROR - currency-rate-service broke its contract")
    void answerWithoutBody() {
        // given
        CurrencyRateServiceClient client = clientAnswering(new AtomicReference<>(), ClientResponse.create(HttpStatus.OK).build());

        // when / then
        expectError(client.getRate("UAH", "EUR"), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("given 5 failures of 5 calls, when asking again, then the open breaker refuses at once without a request")
    void openBreakerRefusesWithoutRequest() {
        // given
        AtomicInteger requests = new AtomicInteger();
        CircuitBreakerRegistry breakers = platformBreakers();
        CurrencyRateServiceClient client = client(request -> {
            requests.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
        }, breakers);
        for (int call = 0; call < 5; call++) {
            expectError(client.getRate("UAH", "EUR"), ErrorCode.DEPENDENCY_UNAVAILABLE);
        }

        // when / then
        expectError(client.getRate("UAH", "EUR"), ErrorCode.DEPENDENCY_UNAVAILABLE);

        // then
        assertThat(breakers.circuitBreaker(BREAKER).getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(requests).hasValue(5);
    }

    @Test
    @DisplayName("given 5 answers 404, when asking again, then the breaker stays closed and the request goes out")
    void notFoundKeepsBreakerClosed() {
        // given
        AtomicInteger requests = new AtomicInteger();
        CircuitBreakerRegistry breakers = platformBreakers();
        CurrencyRateServiceClient client = client(request -> {
            requests.incrementAndGet();
            return Mono.just(json(HttpStatus.NOT_FOUND, PROBLEM_JSON));
        }, breakers);
        for (int call = 0; call < 5; call++) {
            expectError(client.getRate("UAH", "GBP"), ErrorCode.RATE_NOT_FOUND);
        }

        // when / then
        expectError(client.getRate("UAH", "GBP"), ErrorCode.RATE_NOT_FOUND);

        // then
        assertThat(breakers.circuitBreaker(BREAKER).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(requests).hasValue(6);
    }

    // the same numbers as resilience4j.circuitbreaker.configs.default in application.yml
    private static CircuitBreakerRegistry platformBreakers() {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordException(new DependencyFailurePredicate())
                .build());
    }

    private static void expectError(Mono<?> call, ErrorCode expected) {
        StepVerifier.create(call)
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getErrorCode()).isEqualTo(expected))
                .verify();
    }

    private static CurrencyRateServiceClient clientAnswering(AtomicReference<ClientRequest> sent, ClientResponse response) {
        return client(request -> {
            sent.set(request);
            return Mono.just(response);
        }, CircuitBreakerRegistry.ofDefaults());
    }

    private static CurrencyRateServiceClient client(ExchangeFunction exchange, CircuitBreakerRegistry breakers) {
        WebClient webClient = WebClient.builder()
                .baseUrl(PROPERTIES.baseUrl())
                .exchangeFunction(exchange)
                .build();
        CurrencyRateApi api = HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient))
                .build()
                .createClient(CurrencyRateApi.class);
        return new CurrencyRateServiceClient(api, PROPERTIES, new AuthMetrics(new SimpleMeterRegistry()), breakers);
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
