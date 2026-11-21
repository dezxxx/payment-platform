package com.dezxxx.individuals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.individuals.api.model.TokenResponseDto;
import com.dezxxx.individuals.integration.support.IntegrationTest;
import com.dezxxx.individuals.integration.support.StubTransactionService.Received;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@DisplayName("Transfers between currencies: individuals-api with currency-rate-service and transaction-service stubbed")
class CurrencyRateIT extends IntegrationTest {

    private static final String TRANSFER_INIT = "/api/v1/transactions/transfer/init";

    private static final String TRANSFER_CONFIRM = "/api/v1/transactions/transfer/confirm";

    private static final String OFFER_TOKEN = "payload.signature";

    private static final String PASSWORD = "Str0ngPass!";

    private static final UUID SOURCE_WALLET_UID = UUID.fromString("5a1f0c2e-7b3d-4e8f-9a6c-2d4b6f8a0c1e");

    private static final UUID TARGET_WALLET_UID = UUID.fromString("8e2d4f6a-1c3b-4a5d-8f7e-9b0c2d4e6f8a");

    private static final String OFFER_JSON = """
            {"amount":150.00,"fee":0.75,"total":150.75,"expiresAt":"2026-11-14T10:05:00Z","offerToken":"payload.signature",
             "rate":0.01994710,"targetAmount":2.99}
            """;

    private static String accessToken;

    @BeforeEach
    void freshStubsAndUser() {
        TRANSACTION_SERVICE.reset();
        CURRENCY_RATE_SERVICE.reset();
        if (accessToken == null) {
            accessToken = register(freshEmail("it-rate")).getAccessToken();
        }
    }

    @Test
    @DisplayName("given UAH and EUR wallets, when initialising a transfer, then both rates come from currency-rate-service"
            + " and a rate sent by the client is ignored")
    void transferBetweenCurrenciesGetsRates() {
        // given
        TRANSACTION_SERVICE.answerWallets(wallets("UAH", "EUR"));
        TRANSACTION_SERVICE.answer(200, OFFER_JSON);
        CURRENCY_RATE_SERVICE.answerRate("UAH", "EUR", "0.01994710");
        CURRENCY_RATE_SERVICE.answerRate("UAH", "USD", "0.02198874");

        // when / then
        initTransfer("{\"walletUid\":\"%s\",\"targetWalletUid\":\"%s\",\"amount\":150.00,\"rate\":999}"
                .formatted(SOURCE_WALLET_UID, TARGET_WALLET_UID))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.rate").isEqualTo(0.0199471)
                .jsonPath("$.targetAmount").isEqualTo(2.99);

        // then
        List<Received> calls = TRANSACTION_SERVICE.received();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).uri()).isEqualTo("/api/v1/wallets/user/" + PERSON_SERVICE.userUid());
        assertThat(calls.get(1).uri()).isEqualTo(TRANSFER_INIT);
        assertThat(calls.get(1).body())
                .contains("\"sourceCode\":\"UAH\"")
                .contains("\"destinationCode\":\"EUR\"")
                .contains("\"rate\":0.01994710")
                .contains("\"rateToUsd\":0.02198874")
                .doesNotContain("999");
        assertThat(CURRENCY_RATE_SERVICE.received())
                .extracting(call -> call.uri())
                .containsExactlyInAnyOrder("/api/v1/rates?from=UAH&to=EUR", "/api/v1/rates?from=UAH&to=USD");
        assertThat(CURRENCY_RATE_SERVICE.received())
                .allSatisfy(call -> assertThat(call.authorization()).startsWith("Bearer "));
    }

    @Test
    @DisplayName("given USD and EUR wallets, when initialising a transfer, then one rate call and rateToUsd is 1")
    void transferFromUsdAsksOneRate() {
        // given
        TRANSACTION_SERVICE.answerWallets(wallets("USD", "EUR"));
        TRANSACTION_SERVICE.answer(200, OFFER_JSON);
        CURRENCY_RATE_SERVICE.answerRate("USD", "EUR", "0.90715000");

        // when / then
        initTransfer(transferBody()).expectStatus().isOk();

        // then
        assertThat(CURRENCY_RATE_SERVICE.received())
                .extracting(call -> call.uri())
                .containsExactly("/api/v1/rates?from=USD&to=EUR");
        assertThat(TRANSACTION_SERVICE.received().get(1).body())
                .contains("\"rate\":0.90715000")
                .contains("\"rateToUsd\":1");
    }

    @Test
    @DisplayName("given UAH and USD wallets, when initialising a transfer, then UAH -> USD is asked twice and goes as rate and rateToUsd")
    void transferToUsdAsksTheSameRateTwice() {
        // given
        TRANSACTION_SERVICE.answerWallets(wallets("UAH", "USD"));
        TRANSACTION_SERVICE.answer(200, OFFER_JSON);
        CURRENCY_RATE_SERVICE.answerRate("UAH", "USD", "0.02198874");

        // when / then
        initTransfer(transferBody()).expectStatus().isOk();

        // then
        assertThat(CURRENCY_RATE_SERVICE.received())
                .extracting(call -> call.uri())
                .containsExactly("/api/v1/rates?from=UAH&to=USD", "/api/v1/rates?from=UAH&to=USD");
        assertThat(TRANSACTION_SERVICE.received().get(1).body())
                .contains("\"rate\":0.02198874")
                .contains("\"rateToUsd\":0.02198874");
    }

    @Test
    @DisplayName("given two UAH wallets, when initialising a transfer, then currency-rate-service is not called and no rate is sent")
    void transferInOneCurrencyAsksNoRate() {
        // given
        TRANSACTION_SERVICE.answerWallets(wallets("UAH", "UAH"));
        TRANSACTION_SERVICE.answer(200, OFFER_JSON);

        // when / then
        initTransfer(transferBody()).expectStatus().isOk();

        // then
        assertThat(CURRENCY_RATE_SERVICE.received()).isEmpty();
        assertThat(TRANSACTION_SERVICE.received().get(1).body())
                .doesNotContain("rate")
                .doesNotContain("sourceCode");
    }

    @Test
    @DisplayName("given no valid rate for the pair, when initialising a transfer, then 422 RATE_NOT_FOUND and no terms are asked")
    void transferWithoutRate() {
        // given
        TRANSACTION_SERVICE.answerWallets(wallets("UAH", "EUR"));
        CURRENCY_RATE_SERVICE.answerRate("UAH", "USD", "0.02198874");

        // when / then
        initTransfer(transferBody())
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.error").isEqualTo("RATE_NOT_FOUND")
                .jsonPath("$.message").isEqualTo("No valid rate for the currency pair");

        // then
        assertThat(TRANSACTION_SERVICE.received()).hasSize(1);
    }

    @Test
    @DisplayName("given currency-rate-service fails, when initialising a transfer, then 503 DEPENDENCY_UNAVAILABLE and no terms are asked")
    void transferWhenRatesFail() {
        // given
        TRANSACTION_SERVICE.answerWallets(wallets("UAH", "EUR"));
        CURRENCY_RATE_SERVICE.answerProblem(500, "INTERNAL_ERROR");

        // when / then
        initTransfer(transferBody())
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error").isEqualTo("DEPENDENCY_UNAVAILABLE");

        // then
        assertThat(TRANSACTION_SERVICE.received()).hasSize(1);
    }

    @Test
    @DisplayName("given currency-rate-service answers after the 1 s timeout, when initialising a transfer, then 503 DEPENDENCY_UNAVAILABLE")
    void transferWhenRatesAreLate() {
        // given
        TRANSACTION_SERVICE.answerWallets(wallets("UAH", "EUR"));
        CURRENCY_RATE_SERVICE.answerRate("UAH", "EUR", "0.01994710");
        CURRENCY_RATE_SERVICE.answerRate("UAH", "USD", "0.02198874");
        CURRENCY_RATE_SERVICE.answerLate(Duration.ofSeconds(3));

        // when / then
        initTransfer(transferBody())
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error").isEqualTo("DEPENDENCY_UNAVAILABLE");

        // then
        assertThat(TRANSACTION_SERVICE.received()).hasSize(1);
    }

    @Test
    @DisplayName("given currency-rate-service refuses our service token, when initialising a transfer, then 500 - our bug, not the client's")
    void refusedServiceTokenIsOurBug() {
        // given
        TRANSACTION_SERVICE.answerWallets(wallets("UAH", "EUR"));
        CURRENCY_RATE_SERVICE.answerProblem(403, "ACCESS_DENIED");

        // when / then
        initTransfer(transferBody())
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.error").isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("given currency-rate-service failed 5 times in a row, when initialising a transfer, then the breaker is open:"
            + " 503 at once, currency-rate-service is not called")
    void openBreakerSkipsCurrencyRateService() {
        // given
        TRANSACTION_SERVICE.answerWallets(wallets("USD", "EUR"));
        CURRENCY_RATE_SERVICE.answerProblem(500, "INTERNAL_ERROR");
        for (int call = 0; call < 5; call++) {
            initTransfer(transferBody()).expectStatus().isEqualTo(503);
        }
        assertThat(CURRENCY_RATE_SERVICE.received()).hasSize(5);
        CURRENCY_RATE_SERVICE.reset();
        CURRENCY_RATE_SERVICE.answerRate("USD", "EUR", "0.90715000");

        // when / then
        initTransfer(transferBody())
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error").isEqualTo("DEPENDENCY_UNAVAILABLE");

        // then
        assertThat(CURRENCY_RATE_SERVICE.received()).isEmpty();
    }

    @Test
    @DisplayName("given transaction-service answers CURRENCY_MISMATCH, when initialising a transfer, then 500 - our rates were wrong")
    void currencyMismatchIsOurBug() {
        // given
        TRANSACTION_SERVICE.answerWallets(wallets("UAH", "EUR"));
        TRANSACTION_SERVICE.answerProblem(400, "CURRENCY_MISMATCH", "[]");
        CURRENCY_RATE_SERVICE.answerRate("UAH", "EUR", "0.01994710");
        CURRENCY_RATE_SERVICE.answerRate("UAH", "USD", "0.02198874");

        // when / then
        initTransfer(transferBody())
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.error").isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("given a conversion below the minimum, when initialising a transfer, then 400 AMOUNT_TOO_SMALL")
    void amountTooSmallPassesThrough() {
        // given
        TRANSACTION_SERVICE.answerWallets(wallets("UAH", "EUR"));
        TRANSACTION_SERVICE.answerProblem(400, "AMOUNT_TOO_SMALL", "[]");
        CURRENCY_RATE_SERVICE.answerRate("UAH", "EUR", "0.01994710");
        CURRENCY_RATE_SERVICE.answerRate("UAH", "USD", "0.02198874");

        // when / then
        initTransfer(transferBody())
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("AMOUNT_TOO_SMALL")
                .jsonPath("$.message").isEqualTo("The amount is below the minimum conversion amount");
    }

    @Test
    @DisplayName("given an offerToken, when confirming a transfer, then transaction-service gets it as is with X-User-Uid"
            + " from the token")
    void transferConfirmGoesToTransactionService() {
        // given
        TRANSACTION_SERVICE.answer(200, "{\"transactionId\":\"%s\",\"status\":\"COMPLETED\"}".formatted(UUID.randomUUID()));

        // when / then
        client.post().uri(TRANSFER_CONFIRM)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"offerToken\":\"" + OFFER_TOKEN + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("COMPLETED");

        // then
        assertThat(TRANSACTION_SERVICE.received()).hasSize(1);
        Received sent = TRANSACTION_SERVICE.received().getFirst();
        assertThat(sent.uri()).isEqualTo(TRANSFER_CONFIRM);
        assertThat(sent.userUid()).isEqualTo(PERSON_SERVICE.userUid().toString());
        assertThat(sent.body()).isEqualTo("{\"offerToken\":\"" + OFFER_TOKEN + "\"}");
        assertThat(CURRENCY_RATE_SERVICE.received()).isEmpty();
    }

    private WebTestClient.ResponseSpec initTransfer(String body) {
        return client.post().uri(TRANSFER_INIT)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private static String transferBody() {
        return "{\"walletUid\":\"%s\",\"targetWalletUid\":\"%s\",\"amount\":150.00}"
                .formatted(SOURCE_WALLET_UID, TARGET_WALLET_UID);
    }

    private static String wallets(String sourceCurrency, String targetCurrency) {
        return """
                [{"uid":"%s","name":"Source","walletTypeUid":"%s","currencyCode":"%s","status":"ACTIVE","balance":200},
                 {"uid":"%s","name":"Target","walletTypeUid":"%s","currencyCode":"%s","status":"ACTIVE","balance":0}]
                """.formatted(SOURCE_WALLET_UID, UUID.randomUUID(), sourceCurrency,
                TARGET_WALLET_UID, UUID.randomUUID(), targetCurrency);
    }

    private TokenResponseDto register(String email) {
        TokenResponseDto tokens = client.post()
                .uri("/api/v1/auth/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registrationBody(email, PASSWORD, PASSWORD))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TokenResponseDto.class)
                .returnResult()
                .getResponseBody();

        assertThat(tokens).isNotNull();
        return tokens;
    }
}
