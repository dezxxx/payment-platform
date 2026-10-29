package com.dezxxx.transaction.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.api.model.TransactionStatusDto;
import com.dezxxx.transaction.api.model.WithdrawalInitRequestDto;
import com.dezxxx.transaction.integration.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

@DisplayName("TransactionRestControllerV1: withdrawal against two real shards")
class TransactionWithdrawalIT extends IntegrationTest {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private static final BigDecimal FUNDS = new BigDecimal("150.00");

    @Test
    @DisplayName("given exactly amount + fee on the wallet, when init, then 200 with fee 1.00, total 101.00 and an"
            + " offerToken, nothing written")
    void initsWithdrawal() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        fund(wallet, user, new BigDecimal("101.00"));

        // when
        TransactionInitResponseDto offer = post(user, "/api/v1/transactions/withdrawal/init",
                new WithdrawalInitRequestDto().walletUid(wallet).amount(AMOUNT))
                .expectStatus().isOk()
                .expectBody(TransactionInitResponseDto.class)
                .returnResult()
                .getResponseBody();

        // then
        assertThat(offer.getAmount()).isEqualByComparingTo("100.00");
        assertThat(offer.getFee()).isEqualByComparingTo("1.00");
        assertThat(offer.getTotal()).isEqualByComparingTo("101.00");
        assertThat(offer.getOfferToken()).isNotBlank();
        assertThat(transactions(user)).isZero();
        assertThat(balance(wallet, user)).isEqualByComparingTo("101.00");
    }

    @Test
    @DisplayName("given the amount but not the fee on the wallet, when init, then 400 INSUFFICIENT_FUNDS")
    void initRefusesWithoutFee() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        fund(wallet, user, new BigDecimal("100.99"));

        // when / then
        post(user, "/api/v1/transactions/withdrawal/init",
                new WithdrawalInitRequestDto().walletUid(wallet).amount(AMOUNT))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("given the offer, when confirm, then 200 PENDING, amount + fee debited, the transaction and"
            + " WithdrawalRequested")
    void confirmsWithdrawal() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        fund(wallet, user, FUNDS);

        // when
        TransactionConfirmResponseDto confirmed = confirmedWithdrawal(user, wallet, AMOUNT);

        // then
        assertThat(confirmed.getStatus()).isEqualTo(TransactionStatusDto.PENDING);
        assertThat(balance(wallet, user)).isEqualByComparingTo("49.00");
        Map<String, Object> transaction = jdbc.queryForMap(
                "SELECT type::text AS type, status, wallet_uid, amount, fee, request_id FROM transactions"
                        + " WHERE uid = ? AND user_uid = ?", confirmed.getTransactionId(), user);
        assertThat(transaction).containsEntry("type", "WITHDRAWAL").containsEntry("status", "PENDING")
                .containsEntry("wallet_uid", wallet);
        assertThat(transaction.get("request_id")).isNotNull();
        assertThat((BigDecimal) transaction.get("amount")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) transaction.get("fee")).isEqualByComparingTo("1.00");

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT event_type, payload->>'amount' AS amount, payload->>'currency' AS currency"
                        + " FROM transaction_events WHERE transaction_uid = ? AND user_uid = ?",
                confirmed.getTransactionId(), user);
        assertThat(event).containsEntry("event_type", "WithdrawalRequested")
                .containsEntry("amount", "100.00")
                .containsEntry("currency", "USD");
    }

    @Test
    @DisplayName("given a balance spent after init, when confirm, then 400, nothing written, and the same offerToken"
            + " passes once the funds are back")
    void confirmRollsBackWithoutFunds() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        fund(wallet, user, FUNDS);
        String offerToken = withdrawalOffer(user, wallet, AMOUNT);
        fund(wallet, user, new BigDecimal("50.00"));

        // when
        confirmWithdrawal(user, offerToken)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("INSUFFICIENT_FUNDS");

        // then
        assertThat(transactions(user)).isZero();
        assertThat(events(user)).isZero();
        assertThat(balance(wallet, user)).isEqualByComparingTo("50.00");
        fund(wallet, user, FUNDS);
        assertThat(ok(confirmWithdrawal(user, offerToken)).getStatus()).isEqualTo(TransactionStatusDto.PENDING);
    }

    @Test
    @DisplayName("given a confirmed withdrawal, when confirm repeats with the same offerToken, then the same transaction,"
            + " debited once")
    void repeatedConfirmDebitsOnce() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        fund(wallet, user, new BigDecimal("300.00"));
        String offerToken = withdrawalOffer(user, wallet, AMOUNT);
        TransactionConfirmResponseDto first = ok(confirmWithdrawal(user, offerToken));

        // when
        TransactionConfirmResponseDto second = ok(confirmWithdrawal(user, offerToken));

        // then
        assertThat(second).isEqualTo(first);
        assertThat(balance(wallet, user)).isEqualByComparingTo("199.00");
        assertThat(transactions(user)).isEqualTo(1);
        assertThat(events(user)).isEqualTo(1);
    }

    // a race does not happen on every run: repeated, so both confirms really meet on the wallet
    @RepeatedTest(20)
    @DisplayName("given funds for one withdrawal and two offers, when both confirms race, then one 200, one 400,"
            + " never below zero")
    void concurrentConfirmsDebitOnce() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        fund(wallet, user, FUNDS);
        String firstOffer = withdrawalOffer(user, wallet, AMOUNT);
        String secondOffer = withdrawalOffer(user, wallet, AMOUNT);
        CountDownLatch start = new CountDownLatch(1);

        // when
        List<CompletableFuture<Integer>> confirms = List.of(
                CompletableFuture.supplyAsync(() -> raceConfirm(start, user, firstOffer)),
                CompletableFuture.supplyAsync(() -> raceConfirm(start, user, secondOffer)));
        start.countDown();
        List<Integer> statuses = confirms.stream().map(CompletableFuture::join).toList();

        // then: FOR UPDATE makes the second read the balance after the first debit
        assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        assertThat(balance(wallet, user)).isEqualByComparingTo("49.00");
        assertThat(transactions(user)).isEqualTo(1);
    }

    private int raceConfirm(CountDownLatch start, UUID user, String offerToken) {
        try {
            start.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        return confirmWithdrawal(user, offerToken)
                .returnResult(String.class)
                .getStatus()
                .value();
    }
}
