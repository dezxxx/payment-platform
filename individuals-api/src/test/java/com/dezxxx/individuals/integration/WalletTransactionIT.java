package com.dezxxx.individuals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.individuals.api.model.TokenResponseDto;
import com.dezxxx.individuals.integration.support.IntegrationTest;
import com.dezxxx.individuals.integration.support.StubTransactionService.Received;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@DisplayName("Wallets and transactions through individuals-api, transaction-service stubbed")
class WalletTransactionIT extends IntegrationTest {

    private static final String WALLETS = "/api/v1/wallets";

    private static final String TRANSACTIONS = "/api/v1/transactions";

    private static final String WITHDRAWAL_INIT = TRANSACTIONS + "/withdrawal/init";

    private static final String WITHDRAWAL_CONFIRM = TRANSACTIONS + "/withdrawal/confirm";

    private static final String OFFER_TOKEN = "payload.signature";

    private static final String OFFER_JSON = """
            {"amount":50.00,"fee":1.00,"total":51.00,"expiresAt":"2026-10-29T12:05:00Z","offerToken":"%s"}
            """.formatted(OFFER_TOKEN);

    private static final String PASSWORD = "Str0ngPass!";

    private static final UUID WALLET_UID = UUID.fromString("3b0e6a0c-5f4e-4d1a-9a57-1c2b3d4e5f60");

    private static final UUID USD_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000840");

    private static final String WALLET_JSON = """
            {"uid":"%s","name":"Main USD","walletTypeUid":"%s","currencyCode":"USD","status":"ACTIVE","balance":0}
            """.formatted(WALLET_UID, USD_TYPE);

    private static String accessToken;

    @BeforeEach
    void freshStubAndUser() {
        TRANSACTION_SERVICE.reset();
        if (accessToken == null) {
            accessToken = register(freshEmail("it-tx")).getAccessToken();
        }
    }

    @Test
    @DisplayName("given no token, when reading own wallets, then 401 and transaction-service is not called")
    void noToken() {
        // when / then
        client.get().uri(WALLETS)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("AUTHENTICATION_REQUIRED");

        // then
        assertThat(TRANSACTION_SERVICE.received()).isEmpty();
    }

    @Test
    @DisplayName("given a token, when creating a wallet, then transaction-service gets it with X-User-Uid from the token")
    void createsWalletForTokenUser() {
        // given
        TRANSACTION_SERVICE.answer(201, WALLET_JSON);

        // when / then
        authorized(client.post().uri(WALLETS))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Main USD\",\"walletTypeUid\":\"" + USD_TYPE + "\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.uid").isEqualTo(WALLET_UID.toString())
                .jsonPath("$.currencyCode").isEqualTo("USD");

        // then
        Received sent = onlyCall();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri()).isEqualTo(WALLETS);
        assertThat(sent.userUid()).isEqualTo(PERSON_SERVICE.userUid().toString());
        assertThat(sent.body()).contains("\"name\":\"Main USD\"");
    }

    @Test
    @DisplayName("given a token, when reading own wallets, then the user_uid from the token goes into the path")
    void readsOwnWallets() {
        // given
        TRANSACTION_SERVICE.answer(200, "[" + WALLET_JSON + "]");

        // when / then
        authorized(client.get().uri(WALLETS))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].uid").isEqualTo(WALLET_UID.toString());

        // then
        assertThat(onlyCall().uri()).isEqualTo(WALLETS + "/user/" + PERSON_SERVICE.userUid());
    }

    @Test
    @DisplayName("given filters, when searching, then the enums keep their contract values and userUid comes from the token")
    void searchesWithTokenUser() {
        // when / then
        authorized(client.get().uri(TRANSACTIONS + "?type=deposit&status=COMPLETED&page=0&size=20"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .json("[]");

        // then
        assertThat(onlyCall().uri())
                .startsWith(TRANSACTIONS + "?")
                .contains("userUid=" + PERSON_SERVICE.userUid())
                .contains("type=deposit")
                .contains("status=COMPLETED");
    }

    @Test
    @DisplayName("given an offer from transaction-service, when initialising, then the offerToken and expiresAt reach"
            + " the client unchanged")
    void offerPassesThrough() {
        // given
        TRANSACTION_SERVICE.answer(200, OFFER_JSON);

        // when / then
        authorized(client.post().uri(WITHDRAWAL_INIT))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"walletUid\":\"%s\",\"amount\":50.00}".formatted(WALLET_UID))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.offerToken").isEqualTo(OFFER_TOKEN)
                .jsonPath("$.expiresAt").isEqualTo("2026-10-29T12:05:00Z")
                .jsonPath("$.total").isEqualTo(51.0);

        // then
        assertThat(onlyCall().uri()).isEqualTo(WITHDRAWAL_INIT);
    }

    @Test
    @DisplayName("given an offerToken, when confirming, then transaction-service gets it as is with X-User-Uid from"
            + " the token")
    void offerTokenGoesToTransactionService() {
        // given
        TRANSACTION_SERVICE.answer(200, "{\"transactionId\":\"%s\",\"status\":\"PENDING\"}".formatted(WALLET_UID));

        // when
        confirmWithdrawal().expectStatus().isOk();

        // then
        Received sent = onlyCall();
        assertThat(sent.uri()).isEqualTo(WITHDRAWAL_CONFIRM);
        assertThat(sent.userUid()).isEqualTo(PERSON_SERVICE.userUid().toString());
        assertThat(sent.body()).isEqualTo("{\"offerToken\":\"" + OFFER_TOKEN + "\"}");
    }

    @Test
    @DisplayName("given an empty offerToken, when confirming, then 400 VALIDATION_ERROR and transaction-service is not"
            + " called")
    void emptyOfferTokenStopsHere() {
        // when / then
        authorized(client.post().uri(WITHDRAWAL_CONFIRM))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"offerToken\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[0]").value(detail -> assertThat((String) detail).startsWith("offerToken:"));

        // then
        assertThat(TRANSACTION_SERVICE.received()).isEmpty();
    }

    @Test
    @DisplayName("given the same wallet twice, when initialising a transfer, then 400 names targetWalletUid and transaction-service is not called")
    void sameWalletStopsHere() {
        // when / then
        authorized(client.post().uri(TRANSACTIONS + "/transfer/init"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"walletUid\":\"%s\",\"targetWalletUid\":\"%s\",\"amount\":10.00}".formatted(WALLET_UID, WALLET_UID))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[0]")
                .isEqualTo("targetWalletUid: walletUid and targetWalletUid must be different wallets");

        // then
        assertThat(TRANSACTION_SERVICE.received()).isEmpty();
    }

    @Test
    @DisplayName("given not enough money, when confirming, then 400 INSUFFICIENT_FUNDS in our error format")
    void insufficientFundsPassesThrough() {
        // given
        TRANSACTION_SERVICE.answerProblem(400, "INSUFFICIENT_FUNDS", "[]");

        // when / then
        confirmWithdrawal()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("INSUFFICIENT_FUNDS")
                .jsonPath("$.message").isEqualTo("Not enough funds including the fee")
                .jsonPath("$.path").isEqualTo(WITHDRAWAL_CONFIRM)
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("given another user's wallet, when initialising, then 404 WALLET_NOT_FOUND")
    void walletNotFoundPassesThrough() {
        // given
        TRANSACTION_SERVICE.answerProblem(404, "WALLET_NOT_FOUND", "[]");

        // when / then
        authorized(client.post().uri(WITHDRAWAL_INIT))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"walletUid\":\"%s\",\"amount\":50.00}".formatted(WALLET_UID))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("WALLET_NOT_FOUND");
    }

    @Test
    @DisplayName("given a forged or foreign offerToken, when confirming, then 400 OFFER_TOKEN_INVALID")
    void offerTokenInvalidPassesThrough() {
        // given
        TRANSACTION_SERVICE.answerProblem(400, "OFFER_TOKEN_INVALID", "[]");

        // when / then
        confirmWithdrawal()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("OFFER_TOKEN_INVALID")
                .jsonPath("$.message").isEqualTo("The offer token is invalid");
    }

    @Test
    @DisplayName("given an offer older than 5 minutes, when confirming, then 400 OFFER_EXPIRED")
    void offerExpiredPassesThrough() {
        // given
        TRANSACTION_SERVICE.answerProblem(400, "OFFER_EXPIRED", "[]");

        // when / then
        confirmWithdrawal()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("OFFER_EXPIRED")
                .jsonPath("$.message").isEqualTo("The offer token is expired");
    }

    @Test
    @DisplayName("given transaction-service cannot read our body, when confirming, then 500 - our bug, not the client's")
    void malformedRequestIsOurBug() {
        // given
        TRANSACTION_SERVICE.answerProblem(400, "MALFORMED_REQUEST", "[]");

        // when / then
        confirmWithdrawal()
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.error").isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("given transaction-service fails, when confirming, then 503 DEPENDENCY_UNAVAILABLE")
    void serverErrorIsUnavailable() {
        // given
        TRANSACTION_SERVICE.answerProblem(500, "INTERNAL_ERROR", "[]");

        // when / then
        confirmWithdrawal()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error").isEqualTo("DEPENDENCY_UNAVAILABLE");
    }

    @Test
    @DisplayName("given transaction-service answers too late, when reading own wallets, then 503 DEPENDENCY_UNAVAILABLE")
    void lateAnswerIsUnavailable() {
        // given
        TRANSACTION_SERVICE.answerLate(Duration.ofSeconds(3));

        // when / then
        authorized(client.get().uri(WALLETS))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error").isEqualTo("DEPENDENCY_UNAVAILABLE");
    }

    private WebTestClient.ResponseSpec confirmWithdrawal() {
        return authorized(client.post().uri(WITHDRAWAL_CONFIRM))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"offerToken\":\"" + OFFER_TOKEN + "\"}")
                .exchange();
    }

    private static <S extends WebTestClient.RequestHeadersSpec<?>> S authorized(S request) {
        request.header("Authorization", "Bearer " + accessToken);
        return request;
    }

    private static Received onlyCall() {
        assertThat(TRANSACTION_SERVICE.received()).hasSize(1);
        return TRANSACTION_SERVICE.received().getFirst();
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
