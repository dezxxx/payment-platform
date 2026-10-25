package com.dezxxx.transaction.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.transaction.api.model.DepositInitRequestDto;
import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.api.model.TransactionStatusDto;
import com.dezxxx.transaction.integration.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("TransactionRestControllerV1: deposit against two real shards")
class TransactionRestControllerV1IT extends IntegrationTest {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    @Test
    @DisplayName("given the user's wallet, when init, then 200 with amount, fee 0, total and a requestId, nothing written")
    void initsDeposit() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);

        // when
        TransactionInitResponseDto terms = post(user, "/api/v1/transactions/deposit/init",
                new DepositInitRequestDto().walletUid(wallet).amount(AMOUNT))
                .expectStatus().isOk()
                .expectBody(TransactionInitResponseDto.class)
                .returnResult()
                .getResponseBody();

        // then
        assertThat(terms.getAmount()).isEqualByComparingTo("100.00");
        assertThat(terms.getFee()).isEqualByComparingTo("0");
        assertThat(terms.getTotal()).isEqualByComparingTo("100.00");
        assertThat(terms.getRequestId()).isNotNull();
        assertThat(transactions(user)).isZero();
    }

    @Test
    @DisplayName("given another user's wallet, when init, then 404 WALLET_NOT_FOUND")
    void initRefusesForeignWallet() {
        // given
        UUID wallet = createWallet(UUID.randomUUID());

        // when / then
        post(UUID.randomUUID(), "/api/v1/transactions/deposit/init",
                new DepositInitRequestDto().walletUid(wallet).amount(AMOUNT))
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("WALLET_NOT_FOUND");
    }

    @Test
    @DisplayName("given an amount with 3 decimals, when init, then 400 VALIDATION_ERROR naming the field")
    void initRefusesThreeDecimals() {
        // when / then
        post(UUID.randomUUID(), "/api/v1/transactions/deposit/init",
                new DepositInitRequestDto().walletUid(UUID.randomUUID()).amount(new BigDecimal("1.234")))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[0]").value(detail -> assertThat((String) detail).startsWith("amount:"));
    }

    @Test
    @DisplayName("given a zero amount, when confirm, then 400 VALIDATION_ERROR and nothing written")
    void confirmRefusesZeroAmount() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);

        // when / then
        confirm(user, UUID.randomUUID(), wallet, BigDecimal.ZERO)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR");
        assertThat(transactions(user)).isZero();
    }

    @Test
    @DisplayName("given the terms, when confirm, then 200 PENDING, the transaction and its DepositRequested in one shard")
    void confirmsDeposit() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        UUID requestId = UUID.randomUUID();

        // when
        TransactionConfirmResponseDto confirmed = confirmed(user, requestId, wallet, AMOUNT);

        // then
        assertThat(confirmed.getStatus()).isEqualTo(TransactionStatusDto.PENDING);
        Map<String, Object> transaction = jdbc.queryForMap(
                "SELECT type::text AS type, status, wallet_uid, amount, fee, request_id FROM transactions"
                        + " WHERE uid = ? AND user_uid = ?", confirmed.getTransactionId(), user);
        assertThat(transaction).containsEntry("type", "DEPOSIT").containsEntry("status", "PENDING")
                .containsEntry("wallet_uid", wallet).containsEntry("request_id", requestId);
        assertThat((BigDecimal) transaction.get("amount")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) transaction.get("fee")).isEqualByComparingTo("0");

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT event_type, payload->>'transactionId' AS transaction_id, payload->>'userId' AS user_id,"
                        + " payload->>'currency' AS currency, payload->>'amount' AS amount, traceparent"
                        + " FROM transaction_events WHERE transaction_uid = ? AND user_uid = ?",
                confirmed.getTransactionId(), user);
        assertThat(event).containsEntry("event_type", "DepositRequested")
                .containsEntry("transaction_id", confirmed.getTransactionId().toString())
                .containsEntry("user_id", user.toString())
                .containsEntry("currency", "USD")
                .containsEntry("amount", "100.00");
        assertThat((String) event.get("traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

        assertThat(rows(shard(SHARD_0), user) + rows(shard(SHARD_1), user)).isEqualTo(1);
    }

    @Test
    @DisplayName("given a confirmed deposit, when confirm repeats with the same requestId, then 200 with the same transaction, nothing new")
    void repeatedConfirmReturnsSameTransaction() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        UUID requestId = UUID.randomUUID();
        TransactionConfirmResponseDto first = confirmed(user, requestId, wallet, AMOUNT);

        // when
        TransactionConfirmResponseDto second = confirmed(user, requestId, wallet, new BigDecimal("100"));

        // then
        assertThat(second).isEqualTo(first);
        assertThat(transactions(user)).isEqualTo(1);
        assertThat(events(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("given a confirmed deposit, when its requestId comes with another amount, then 409 REQUEST_ID_CONFLICT")
    void confirmRefusesReusedRequestId() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        UUID requestId = UUID.randomUUID();
        confirmed(user, requestId, wallet, AMOUNT);

        // when / then
        confirm(user, requestId, wallet, new BigDecimal("200.00"))
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").isEqualTo("REQUEST_ID_CONFLICT");
        assertThat(transactions(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("given an unknown wallet, when confirm, then 404, nothing written and the requestId stays free")
    void confirmRollsBackUnknownWallet() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        UUID requestId = UUID.randomUUID();

        // when
        confirm(user, requestId, UUID.randomUUID(), AMOUNT)
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("WALLET_NOT_FOUND");

        // then
        assertThat(transactions(user)).isZero();
        assertThat(confirmed(user, requestId, wallet, AMOUNT).getStatus()).isEqualTo(TransactionStatusDto.PENDING);
    }

    private static int rows(JdbcTemplate shard, UUID user) {
        Integer count = shard.queryForObject("SELECT count(*) FROM transactions WHERE user_uid = ?", Integer.class, user);
        return count == null ? 0 : count;
    }
}
