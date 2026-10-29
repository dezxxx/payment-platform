package com.dezxxx.transaction.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.transaction.api.model.CreateWalletRequestDto;
import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.api.model.TransactionStatusDto;
import com.dezxxx.transaction.api.model.TransferInitRequestDto;
import com.dezxxx.transaction.api.model.WalletResponseDto;
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
import org.springframework.test.web.servlet.client.RestTestClient;

@DisplayName("TransactionRestControllerV1: transfer against two real shards")
class TransactionTransferIT extends IntegrationTest {

    private static final UUID EUR_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000978");

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private static final BigDecimal FUNDS = new BigDecimal("150.00");

    @Test
    @DisplayName("given two wallets of the user, when init, then 200 with fee 0.50, total 100.50 and an offerToken,"
            + " nothing written")
    void initsTransfer() {
        // given
        UUID user = UUID.randomUUID();
        UUID source = createWallet(user);
        UUID target = createWallet(user);
        fund(source, user, FUNDS);

        // when
        TransactionInitResponseDto offer = post(user, "/api/v1/transactions/transfer/init",
                new TransferInitRequestDto().walletUid(source).targetWalletUid(target).amount(AMOUNT))
                .expectStatus().isOk()
                .expectBody(TransactionInitResponseDto.class)
                .returnResult()
                .getResponseBody();

        // then
        assertThat(offer.getFee()).isEqualByComparingTo("0.50");
        assertThat(offer.getTotal()).isEqualByComparingTo("100.50");
        assertThat(offer.getOfferToken()).isNotBlank();
        assertThat(transactions(user)).isZero();
    }

    @Test
    @DisplayName("given a USD and an EUR wallet, when init, then 400 CURRENCY_MISMATCH and no offer")
    void initRefusesOtherCurrency() {
        // given
        UUID user = UUID.randomUUID();
        UUID source = createWallet(user);
        UUID target = createEurWallet(user);
        fund(source, user, FUNDS);

        // when / then
        initTransfer(user, source, target)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    @DisplayName("given an unknown target wallet, when init, then 404 WALLET_NOT_FOUND and no offer")
    void initRefusesUnknownTarget() {
        // given
        UUID user = UUID.randomUUID();
        UUID source = createWallet(user);
        fund(source, user, FUNDS);

        // when / then
        initTransfer(user, source, UUID.randomUUID())
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("WALLET_NOT_FOUND");
    }

    @Test
    @DisplayName("given the same wallet twice, when init, then 400 VALIDATION_ERROR before any database work")
    void initRefusesSameWallet() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);

        // when / then
        initTransfer(user, wallet, wallet)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[0]").isEqualTo("targetWalletUid: walletUid and targetWalletUid must be different wallets");
    }

    @Test
    @DisplayName("given the offer, when confirm, then 200 COMPLETED at once: amount + fee off the source, amount on"
            + " the target, no event")
    void confirmsTransfer() {
        // given
        UUID user = UUID.randomUUID();
        UUID source = createWallet(user);
        UUID target = createWallet(user);
        fund(source, user, FUNDS);

        // when
        TransactionConfirmResponseDto confirmed = confirmedTransfer(user, source, target, AMOUNT);

        // then
        assertThat(confirmed.getStatus()).isEqualTo(TransactionStatusDto.COMPLETED);
        assertThat(balance(source, user)).isEqualByComparingTo("49.50");
        assertThat(balance(target, user)).isEqualByComparingTo("100.00");
        Map<String, Object> transaction = jdbc.queryForMap(
                "SELECT type::text AS type, status, wallet_uid, target_wallet_uid, amount, fee, request_id"
                        + " FROM transactions WHERE uid = ? AND user_uid = ?", confirmed.getTransactionId(), user);
        assertThat(transaction).containsEntry("type", "TRANSFER").containsEntry("status", "COMPLETED")
                .containsEntry("wallet_uid", source).containsEntry("target_wallet_uid", target);
        assertThat(transaction.get("request_id")).isNotNull();
        assertThat((BigDecimal) transaction.get("fee")).isEqualByComparingTo("0.50");
        assertThat(events(user)).isZero();
    }

    @Test
    @DisplayName("given the fee spent from the source after init, when confirm, then 400, nothing written, and the same"
            + " offerToken passes once the funds are back")
    void confirmRollsBackWithoutFee() {
        // given
        UUID user = UUID.randomUUID();
        UUID source = createWallet(user);
        UUID target = createWallet(user);
        fund(source, user, FUNDS);
        String offerToken = transferOffer(user, source, target, AMOUNT);
        fund(source, user, new BigDecimal("100.49"));

        // when
        confirmTransfer(user, offerToken)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("INSUFFICIENT_FUNDS");

        // then
        assertThat(transactions(user)).isZero();
        assertThat(balance(target, user)).isEqualByComparingTo("0");
        fund(source, user, FUNDS);
        assertThat(ok(confirmTransfer(user, offerToken)).getStatus()).isEqualTo(TransactionStatusDto.COMPLETED);
    }

    @Test
    @DisplayName("given a completed transfer, when confirm repeats with the same offerToken, then the same transaction,"
            + " money moved once")
    void repeatedConfirmMovesOnce() {
        // given
        UUID user = UUID.randomUUID();
        UUID source = createWallet(user);
        UUID target = createWallet(user);
        fund(source, user, new BigDecimal("300.00"));
        String offerToken = transferOffer(user, source, target, AMOUNT);
        TransactionConfirmResponseDto first = ok(confirmTransfer(user, offerToken));

        // when
        TransactionConfirmResponseDto second = ok(confirmTransfer(user, offerToken));

        // then
        assertThat(second).isEqualTo(first);
        assertThat(balance(source, user)).isEqualByComparingTo("199.50");
        assertThat(balance(target, user)).isEqualByComparingTo("100.00");
        assertThat(transactions(user)).isEqualTo(1);
    }

    // a race does not happen on every run: repeated, so both confirms really meet on the two wallets
    @RepeatedTest(20)
    @DisplayName("given two wallets, when A→B and B→A are confirmed at once, then both 200, no deadlock")
    void oppositeTransfersDoNotDeadlock() {
        // given
        UUID user = UUID.randomUUID();
        UUID first = createWallet(user);
        UUID second = createWallet(user);
        fund(first, user, FUNDS);
        fund(second, user, FUNDS);
        String forward = transferOffer(user, first, second, AMOUNT);
        String backward = transferOffer(user, second, first, AMOUNT);
        CountDownLatch start = new CountDownLatch(1);

        // when
        List<CompletableFuture<Integer>> confirms = List.of(
                CompletableFuture.supplyAsync(() -> raceConfirm(start, user, forward)),
                CompletableFuture.supplyAsync(() -> raceConfirm(start, user, backward)));
        start.countDown();
        List<Integer> statuses = confirms.stream().map(CompletableFuture::join).toList();

        // then: ORDER BY uid locks the two wallets in one order for both
        assertThat(statuses).containsExactly(200, 200);
        assertThat(balance(first, user)).isEqualByComparingTo("149.50");
        assertThat(balance(second, user)).isEqualByComparingTo("149.50");
    }

    private int raceConfirm(CountDownLatch start, UUID user, String offerToken) {
        try {
            start.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        return confirmTransfer(user, offerToken)
                .returnResult(String.class)
                .getStatus()
                .value();
    }

    private RestTestClient.ResponseSpec initTransfer(UUID user, UUID source, UUID target) {
        return post(user, "/api/v1/transactions/transfer/init",
                new TransferInitRequestDto().walletUid(source).targetWalletUid(target).amount(AMOUNT));
    }

    private UUID createEurWallet(UUID user) {
        return post(user, "/api/v1/wallets", new CreateWalletRequestDto().name("Main EUR").walletTypeUid(EUR_TYPE))
                .expectStatus().isCreated()
                .expectBody(WalletResponseDto.class)
                .returnResult()
                .getResponseBody()
                .getUid();
    }
}
