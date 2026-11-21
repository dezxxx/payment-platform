package com.dezxxx.currency.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.currency.integration.support.IntegrationTest;
import com.dezxxx.currency.integration.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

@DisplayName("currency-rate-service REST")
class CurrencyRateRestIT extends IntegrationTest {

    private static final Instant TEN = Instant.parse("2026-01-10T10:00:00Z");

    private static final Instant TWELVE = Instant.parse("2026-01-10T12:00:00Z");

    @Test
    @DisplayName("given a rate valid now, when getting it, then 200 with the pair, the rate, its begin and the provider")
    void returnsRateValidNow() {
        // given
        Instant begin = Instant.now().minus(Duration.ofMinutes(30));
        insertRate("UAH", "EUR", "0.01994710", begin, begin.plus(Duration.ofHours(3)));

        // when, then
        client.get().uri("/api/v1/rates?from=UAH&to=EUR")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sourceCode").isEqualTo("UAH")
                .jsonPath("$.destinationCode").isEqualTo("EUR")
                .jsonPath("$.rate").isEqualTo(0.0199471)
                .jsonPath("$.providerCode").isEqualTo("OXR");
    }

    @Test
    @DisplayName("given rates from 10:00 and 12:00 both valid at 12:30, when asking for 12:30 and 11:00, then the newest valid one each time")
    void returnsNewestValidAtTimestamp() {
        // given
        insertRate("UAH", "EUR", "0.01990000", TEN, TEN.plus(Duration.ofHours(3)));
        insertRate("UAH", "EUR", "0.01994710", TWELVE, TWELVE.plus(Duration.ofHours(3)));

        // when, then
        client.get().uri("/api/v1/rates?from=UAH&to=EUR&timestamp=2026-01-10T12:30:00Z")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.rate").isEqualTo(0.0199471)
                .jsonPath("$.rateTimestamp").isEqualTo("2026-01-10T12:00:00Z");
        client.get().uri("/api/v1/rates?from=UAH&to=EUR&timestamp=2026-01-10T11:00:00Z")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.rate").isEqualTo(0.0199)
                .jsonPath("$.rateTimestamp").isEqualTo("2026-01-10T10:00:00Z");
    }

    @Test
    @DisplayName("given no rate of the pair, when getting it, then 404 RATE_NOT_FOUND")
    void answersRateNotFound() {
        // when, then
        client.get().uri("/api/v1/rates?from=UAH&to=EUR")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("RATE_NOT_FOUND")
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.instance").isEqualTo("/api/v1/rates");
    }

    @Test
    @DisplayName("given a rate from 10:00 to 13:00, when asking for exactly 13:00, then 404: rate_end_time is no longer valid")
    void endsRateAtEndTimeExclusive() {
        // given
        insertRate("UAH", "EUR", "0.01990000", TEN, TEN.plus(Duration.ofHours(3)));

        // when, then
        client.get().uri("/api/v1/rates?from=UAH&to=EUR&timestamp=2026-01-10T13:00:00Z")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("RATE_NOT_FOUND");
    }

    @Test
    @DisplayName("given from equals to, when getting a rate, then 400 VALIDATION_ERROR")
    void refusesSameCurrency() {
        // when, then
        client.get().uri("/api/v1/rates?from=USD&to=USD")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.detail").isEqualTo("from equals to");
    }

    @Test
    @DisplayName("given a code in small letters, when getting a rate, then 400 VALIDATION_ERROR naming the parameter")
    void refusesLowercaseCode() {
        // when, then
        client.get().uri("/api/v1/rates?from=usd&to=EUR")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[0]").value(detail -> assertThat((String) detail)
                        .startsWith("from:"));
    }

    @Test
    @DisplayName("given no to, when getting a rate, then 400 VALIDATION_ERROR")
    void refusesMissingParameter() {
        // when, then
        client.get().uri("/api/v1/rates?from=USD")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[0]").isEqualTo("to: is required");
    }

    @Test
    @DisplayName("given a timestamp that is not ISO 8601, when getting a rate, then 400 VALIDATION_ERROR naming timestamp")
    void refusesBadTimestamp() {
        // when, then
        client.get().uri("/api/v1/rates?from=UAH&to=EUR&timestamp=yesterday")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[0]").isEqualTo("timestamp: invalid value");
    }

    @Test
    @DisplayName("given no token, when getting a rate, then 401 AUTHENTICATION_REQUIRED in the usual body")
    void refusesWithoutToken() {
        // when, then
        anonymousClient.get().uri("/api/v1/rates?from=UAH&to=EUR")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("given a token signed by another key, when getting a rate, then 401 AUTHENTICATION_REQUIRED")
    void refusesForeignSignature() {
        // when, then
        anonymousClient.get().uri("/api/v1/rates?from=UAH&to=EUR")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.signedByAnotherKey())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("given a user token without the role SERVICE, when getting a rate, then 403 ACCESS_DENIED")
    void refusesUserToken() {
        // when, then
        anonymousClient.get().uri("/api/v1/rates?from=UAH&to=EUR")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.user())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("when listing currencies, then 200 with the four seeded currencies by code")
    void listsCurrencies() {
        // when, then
        client.get().uri("/api/v1/currencies")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(4)
                .jsonPath("$[*].code").isEqualTo(List.of("EUR", "ILS", "UAH", "USD"))
                .jsonPath("$[1].isoCode").isEqualTo("376");
    }

    @Test
    @DisplayName("when listing rate providers, then 200 with OXR first")
    void listsProviders() {
        // when, then
        client.get().uri("/api/v1/rate-providers")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].providerCode").isEqualTo("OXR")
                .jsonPath("$[0].priority").isEqualTo(1);
    }

    @Test
    @DisplayName("given no token, when Docker asks for the health, then 200: the actuator stays open")
    void keepsHealthOpen() {
        // when, then
        anonymousClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("given POST, when calling /rates, then 405 METHOD_NOT_ALLOWED: rates are only read")
    void refusesPost() {
        // when, then
        client.post().uri("/api/v1/rates?from=UAH&to=EUR")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectBody()
                .jsonPath("$.error").isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    @DisplayName("given an unknown path, when calling it, then 404 NOT_FOUND in the usual body")
    void answersUnknownPath() {
        // when, then
        client.get().uri("/api/v1/exchange")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("NOT_FOUND");
    }
}
