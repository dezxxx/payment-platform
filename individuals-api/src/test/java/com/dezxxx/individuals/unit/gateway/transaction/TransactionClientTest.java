package com.dezxxx.individuals.unit.gateway.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.individuals.config.HttpClientsConfig;
import com.dezxxx.individuals.config.TransactionServiceProperties;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.transaction.TransactionClient;
import com.dezxxx.individuals.gateway.transaction.TransactionSearch;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.transaction.client.api.TransactionsApi;
import com.dezxxx.transaction.client.api.WalletsApi;
import com.dezxxx.transaction.client.model.CreateWalletRequestDto;
import com.dezxxx.transaction.client.model.TransactionStatusDto;
import com.dezxxx.transaction.client.model.TransactionTypeDto;
import com.dezxxx.transaction.client.model.WithdrawalConfirmRequestDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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

@DisplayName("TransactionClient")
class TransactionClientTest {

    private static final TransactionServiceProperties PROPERTIES =
            new TransactionServiceProperties("http://transaction-service", Duration.ofSeconds(5));

    private static final UUID USER_UID = UUID.fromString("7fd6212d-0f71-42a0-bf6e-7dbce6cf04fb");

    private static final UUID WALLET_UID = UUID.fromString("3b0e6a0c-5f4e-4d1a-9a57-1c2b3d4e5f60");

    private static final UUID USD_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000840");

    private static final String WALLET_JSON = """
            {"uid":"%s","name":"Main USD","walletTypeUid":"%s","currencyCode":"USD","status":"ACTIVE","balance":0}
            """.formatted(WALLET_UID, USD_TYPE);

    @Test
    @DisplayName("given a new wallet, when creating, then POST /api/v1/wallets carries X-User-Uid and the wallet comes back")
    void createsWallet() {
        // given
        AtomicReference<ClientRequest> sent = new AtomicReference<>();
        TransactionClient client = clientAnswering(sent, json(HttpStatus.CREATED, WALLET_JSON));

        // when / then
        StepVerifier.create(client.createWallet(USER_UID, new CreateWalletRequestDto("Main USD", USD_TYPE)))
                .assertNext(wallet -> assertThat(wallet.getUid()).isEqualTo(WALLET_UID))
                .verifyComplete();

        // then
        assertThat(sent.get().method()).isEqualTo(HttpMethod.POST);
        assertThat(sent.get().url().getPath()).isEqualTo("/api/v1/wallets");
        assertThat(sent.get().headers().getFirst("X-User-Uid")).isEqualTo(USER_UID.toString());
    }

    @Test
    @DisplayName("given the user has a wallet, when reading own wallets, then the user_uid goes into the path and the list is collected")
    void readsOwnWallets() {
        // given
        AtomicReference<ClientRequest> sent = new AtomicReference<>();
        TransactionClient client = clientAnswering(sent, json(HttpStatus.OK, "[" + WALLET_JSON + "]"));

        // when / then
        StepVerifier.create(client.getWalletsByUserUid(USER_UID))
                .assertNext(wallets -> assertThat(wallets).singleElement()
                        .satisfies(wallet -> assertThat(wallet.getUid()).isEqualTo(WALLET_UID)))
                .verifyComplete();

        // then
        assertThat(sent.get().method()).isEqualTo(HttpMethod.GET);
        assertThat(sent.get().url().getPath()).isEqualTo("/api/v1/wallets/user/" + USER_UID);
    }

    @Test
    @DisplayName("given filters, when searching, then userUid is added and the enums go as their contract values")
    void searchesWithUserUid() {
        // given
        AtomicReference<ClientRequest> sent = new AtomicReference<>();
        TransactionClient client = clientAnswering(sent, json(HttpStatus.OK, "[]"));
        TransactionSearch search = new TransactionSearch(WALLET_UID, TransactionTypeDto.DEPOSIT,
                TransactionStatusDto.COMPLETED, null, null, 0, 20);

        // when / then
        StepVerifier.create(client.searchTransactions(USER_UID, search))
                .assertNext(transactions -> assertThat(transactions).isEmpty())
                .verifyComplete();

        // then
        assertThat(sent.get().url().getPath()).isEqualTo("/api/v1/transactions");
        assertThat(sent.get().url().getQuery())
                .contains("userUid=" + USER_UID)
                .contains("walletUid=" + WALLET_UID)
                .contains("type=deposit")
                .contains("status=COMPLETED");
    }

    @Test
    @DisplayName("given not enough money, when confirming a withdrawal, then INSUFFICIENT_FUNDS passes through")
    void insufficientFundsPassesThrough() {
        // given
        TransactionClient client = clientAnswering(new AtomicReference<>(),
                problem(HttpStatus.BAD_REQUEST, "INSUFFICIENT_FUNDS", "[]"));

        // when / then
        expectError(client.confirmWithdrawal(USER_UID, withdrawal()), ErrorCode.INSUFFICIENT_FUNDS);
    }

    @Test
    @DisplayName("given transaction-service rejects a field, when calling, then VALIDATION_ERROR keeps its details")
    void validationKeepsDetails() {
        // given
        TransactionClient client = clientAnswering(new AtomicReference<>(),
                problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "[\"dateFrom: must not be after dateTo\"]"));

        // when / then
        StepVerifier.create(client.searchTransactions(USER_UID, new TransactionSearch(null, null, null, null, null, 0, 20)))
                .expectErrorSatisfies(error -> {
                    ApiException ex = (ApiException) error;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(ex.getDetails()).containsExactly("dateFrom: must not be after dateTo");
                })
                .verify();
    }

    @Test
    @DisplayName("given another user's or a missing wallet, when reading it, then WALLET_NOT_FOUND passes through")
    void walletNotFoundPassesThrough() {
        // given
        TransactionClient client = clientAnswering(new AtomicReference<>(),
                problem(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "[]"));

        // when / then
        expectError(client.getWalletById(USER_UID, WALLET_UID), ErrorCode.WALLET_NOT_FOUND);
    }

    @Test
    @DisplayName("given a requestId used for another operation, when confirming, then REQUEST_ID_CONFLICT passes through")
    void requestIdConflictPassesThrough() {
        // given
        TransactionClient client = clientAnswering(new AtomicReference<>(),
                problem(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT", "[]"));

        // when / then
        expectError(client.confirmWithdrawal(USER_UID, withdrawal()), ErrorCode.REQUEST_ID_CONFLICT);
    }

    @Test
    @DisplayName("given a body transaction-service cannot read, when calling, then INTERNAL_ERROR - we sent it")
    void malformedRequestIsOurBug() {
        // given
        TransactionClient client = clientAnswering(new AtomicReference<>(),
                problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "[]"));

        // when / then
        expectError(client.confirmWithdrawal(USER_UID, withdrawal()), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("given a 4xx without a readable body, when calling, then INTERNAL_ERROR")
    void unreadableErrorBody() {
        // given
        TransactionClient client = clientAnswering(new AtomicReference<>(),
                ClientResponse.create(HttpStatus.BAD_REQUEST).body("not json").build());

        // when / then
        expectError(client.getWalletById(USER_UID, WALLET_UID), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("given transaction-service fails, when calling, then DEPENDENCY_UNAVAILABLE")
    void serverError() {
        // given
        TransactionClient client = clientAnswering(new AtomicReference<>(),
                problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "[]"));

        // when / then
        expectError(client.getWalletsByUserUid(USER_UID), ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    @DisplayName("given transaction-service is unreachable, when calling, then DEPENDENCY_UNAVAILABLE")
    void unreachable() {
        // given
        TransactionClient client = client(request -> Mono.error(new ConnectException("Connection refused")));

        // when / then
        expectError(client.confirmWithdrawal(USER_UID, withdrawal()), ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    @DisplayName("given a 200 without a body, when reading a wallet, then INTERNAL_ERROR - transaction-service broke its contract")
    void answerWithoutBody() {
        // given
        TransactionClient client = clientAnswering(new AtomicReference<>(), ClientResponse.create(HttpStatus.OK).build());

        // when / then
        expectError(client.getWalletById(USER_UID, WALLET_UID), ErrorCode.INTERNAL_ERROR);
    }

    private static void expectError(Mono<?> call, ErrorCode expected) {
        StepVerifier.create(call)
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).getErrorCode()).isEqualTo(expected))
                .verify();
    }

    private static TransactionClient clientAnswering(AtomicReference<ClientRequest> sent, ClientResponse response) {
        return client(request -> {
            sent.set(request);
            return Mono.just(response);
        });
    }

    private static TransactionClient client(ExchangeFunction exchange) {
        WebClient webClient = WebClient.builder()
                .baseUrl(PROPERTIES.baseUrl())
                .exchangeFunction(exchange)
                .build();
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient))
                .conversionService(HttpClientsConfig.transactionQueryValues())
                .build();
        return new TransactionClient(factory.createClient(WalletsApi.class), factory.createClient(TransactionsApi.class),
                PROPERTIES, new AuthMetrics(new SimpleMeterRegistry()));
    }

    private static WithdrawalConfirmRequestDto withdrawal() {
        return new WithdrawalConfirmRequestDto(UUID.randomUUID(), WALLET_UID, new BigDecimal("50.00"));
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static ClientResponse problem(HttpStatus status, String error, String details) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .body("{\"status\":" + status.value() + ",\"error\":\"" + error + "\",\"details\":" + details + "}")
                .build();
    }
}
