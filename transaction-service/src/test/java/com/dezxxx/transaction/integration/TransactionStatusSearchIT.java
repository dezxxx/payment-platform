package com.dezxxx.transaction.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionStatusDto;
import com.dezxxx.transaction.api.model.TransactionStatusResponseDto;
import com.dezxxx.transaction.api.model.TransactionTypeDto;
import com.dezxxx.transaction.api.model.TransferConfirmRequestDto;
import com.dezxxx.transaction.integration.support.IntegrationTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.client.RestTestClient;

@DisplayName("Transaction status and search against two real shards")
class TransactionStatusSearchIT extends IntegrationTest {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private static final ParameterizedTypeReference<List<TransactionStatusResponseDto>> PAGE =
            new ParameterizedTypeReference<>() {
            };

    @Test
    @DisplayName("given the user's deposit, when its status is read, then 200 with the contract's lowercase type")
    void readsStatus() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        UUID transaction = confirmed(user, UUID.randomUUID(), wallet, AMOUNT).getTransactionId();

        // when / then
        status(user, transaction)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.transactionId").isEqualTo(transaction.toString())
                .jsonPath("$.type").isEqualTo("deposit")
                .jsonPath("$.status").isEqualTo("PENDING")
                .jsonPath("$.walletUid").isEqualTo(wallet.toString())
                .jsonPath("$.amount").isEqualTo(100.0)
                .jsonPath("$.createdAt").exists();
    }

    @Test
    @DisplayName("given another user's transaction, when its status is read, then 404 TRANSACTION_NOT_FOUND")
    void refusesForeignStatus() {
        // given
        UUID owner = UUID.randomUUID();
        UUID transaction = confirmed(owner, UUID.randomUUID(), createWallet(owner), AMOUNT).getTransactionId();

        // when / then
        status(UUID.randomUUID(), transaction)
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("TRANSACTION_NOT_FOUND");
    }

    @Test
    @DisplayName("given a deposit, a withdrawal and a transfer, when searching without filters, then all three, newest first")
    void searchesNewestFirst() {
        // given
        History history = history();

        // when
        List<TransactionStatusResponseDto> found = found("userUid=" + history.user());

        // then
        assertThat(found).extracting(TransactionStatusResponseDto::getTransactionId)
                .containsExactly(history.transfer(), history.withdrawal(), history.deposit());
    }

    @Test
    @DisplayName("given the history, when filtering by type in lowercase, then only that type")
    void filtersByType() {
        // given
        History history = history();

        // when
        List<TransactionStatusResponseDto> found = found("userUid=" + history.user() + "&type=withdrawal");

        // then
        assertThat(found).extracting(TransactionStatusResponseDto::getTransactionId)
                .containsExactly(history.withdrawal());
        assertThat(found).extracting(TransactionStatusResponseDto::getType)
                .containsExactly(TransactionTypeDto.WITHDRAWAL);
    }

    @Test
    @DisplayName("given the history, when filtering by status, then only that status")
    void filtersByStatus() {
        // given
        History history = history();

        // when
        List<TransactionStatusResponseDto> found = found("userUid=" + history.user() + "&status=COMPLETED");

        // then
        assertThat(found).extracting(TransactionStatusResponseDto::getTransactionId)
                .containsExactly(history.transfer());
        assertThat(found).extracting(TransactionStatusResponseDto::getStatus)
                .containsExactly(TransactionStatusDto.COMPLETED);
    }

    @Test
    @DisplayName("given the history, when filtering by the source wallet, then only the transactions made from it")
    void filtersByWallet() {
        // given
        History history = history();

        // when
        List<TransactionStatusResponseDto> found = found("userUid=" + history.user()
                + "&walletUid=" + history.source());

        // then
        assertThat(found).extracting(TransactionStatusResponseDto::getTransactionId)
                .containsExactly(history.transfer(), history.withdrawal());
    }

    @Test
    @DisplayName("given the history, when filtering by dates, then only the transactions inside the range")
    void filtersByDates() {
        // given
        History history = history();

        // when
        List<TransactionStatusResponseDto> found = found("userUid=" + history.user()
                + "&dateFrom=2026-10-02T00:00:00Z&dateTo=2026-10-03T00:00:00Z");

        // then
        assertThat(found).extracting(TransactionStatusResponseDto::getTransactionId)
                .containsExactly(history.withdrawal());
    }

    @Test
    @DisplayName("given three transactions, when reading pages of two, then the pages do not overlap")
    void pagesDoNotOverlap() {
        // given
        History history = history();

        // when
        List<TransactionStatusResponseDto> first = found("userUid=" + history.user() + "&page=0&size=2");
        List<TransactionStatusResponseDto> second = found("userUid=" + history.user() + "&page=1&size=2");

        // then
        assertThat(first).extracting(TransactionStatusResponseDto::getTransactionId)
                .containsExactly(history.transfer(), history.withdrawal());
        assertThat(second).extracting(TransactionStatusResponseDto::getTransactionId)
                .containsExactly(history.deposit());
    }

    @Test
    @DisplayName("given another user's wallet, when searching by it, then an empty list, not their transactions")
    void foreignWalletFindsNothing() {
        // given
        History history = history();

        // when
        List<TransactionStatusResponseDto> found = found("userUid=" + UUID.randomUUID()
                + "&walletUid=" + history.source());

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("given dateFrom after dateTo, when searching, then 400 VALIDATION_ERROR")
    void refusesReversedDates() {
        // when / then
        search("userUid=" + UUID.randomUUID() + "&dateFrom=2026-10-03T00:00:00Z&dateTo=2026-10-02T00:00:00Z")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.detail").isEqualTo("dateFrom must not be after dateTo");
    }

    @Test
    @DisplayName("given a page size above 100, when searching, then 400 VALIDATION_ERROR")
    void refusesTooLargePage() {
        // when / then
        search("userUid=" + UUID.randomUUID() + "&size=101")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("given a type the contract does not know, when searching, then 400 VALIDATION_ERROR")
    void refusesUnknownType() {
        // when / then
        search("userUid=" + UUID.randomUUID() + "&type=refund")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR");
    }

    // a deposit on the target wallet (Oct 1), a withdrawal (Oct 2) and a transfer (Oct 3) from the source;
    // the dates are set by hand, so the order does not hang on the clock
    private History history() {
        UUID user = UUID.randomUUID();
        UUID source = createWallet(user);
        UUID target = createWallet(user);
        fund(source, user, new BigDecimal("500.00"));
        UUID deposit = confirmed(user, UUID.randomUUID(), target, AMOUNT).getTransactionId();
        UUID withdrawal = confirmedWithdrawal(user, UUID.randomUUID(), source, AMOUNT).getTransactionId();
        UUID transfer = post(user, "/api/v1/transactions/transfer/confirm", new TransferConfirmRequestDto()
                .requestId(UUID.randomUUID()).walletUid(source).targetWalletUid(target).amount(AMOUNT))
                .expectStatus().isOk()
                .expectBody(TransactionConfirmResponseDto.class)
                .returnResult()
                .getResponseBody()
                .getTransactionId();
        createdAt(deposit, user, "2026-10-01T12:00:00Z");
        createdAt(withdrawal, user, "2026-10-02T12:00:00Z");
        createdAt(transfer, user, "2026-10-03T12:00:00Z");
        return new History(user, source, target, deposit, withdrawal, transfer);
    }

    private void createdAt(UUID transaction, UUID user, String instant) {
        jdbc.update("UPDATE transactions SET created_at = ? WHERE uid = ? AND user_uid = ?",
                Timestamp.from(Instant.parse(instant)), transaction, user);
    }

    private RestTestClient.ResponseSpec status(UUID user, UUID transaction) {
        return client.get().uri("/api/v1/transactions/{id}/status", transaction)
                .header(USER_UID_HEADER, user.toString())
                .exchange();
    }

    private RestTestClient.ResponseSpec search(String query) {
        return client.get().uri("/api/v1/transactions?" + query).exchange();
    }

    private List<TransactionStatusResponseDto> found(String query) {
        return search(query)
                .expectStatus().isOk()
                .expectBody(PAGE)
                .returnResult()
                .getResponseBody();
    }

    private record History(UUID user, UUID source, UUID target, UUID deposit, UUID withdrawal, UUID transfer) {
    }
}
