package com.dezxxx.transaction.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dezxxx.transaction.api.model.DepositInitRequestDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.event.DepositCompleted;
import com.dezxxx.transaction.event.DepositRequested;
import com.dezxxx.transaction.event.EventTopics;
import com.dezxxx.transaction.integration.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

// the platform's deposit fee is 0: a non-zero one shows whether the fee reaches every party
@TestPropertySource(properties = "transaction.fee.deposit=0.02")
@DisplayName("Deposit with a non-zero fee, against real shards and Kafka")
class TransactionDepositFeeIT extends IntegrationTest {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    @Test
    @DisplayName("given a deposit fee of 2 %, when a deposit of 100 goes init → confirm → DepositCompleted,"
            + " then the offer says fee 2 and total 102, the transaction keeps fee 2, DepositRequested carries"
            + " amount 100 and total 102, and the wallet gets 100")
    void chargesTheFeeAndCreditsTheAmount() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);

        // when
        TransactionInitResponseDto offer = post(user, "/api/v1/transactions/deposit/init",
                new DepositInitRequestDto().walletUid(wallet).amount(AMOUNT))
                .expectStatus().isOk()
                .expectBody(TransactionInitResponseDto.class)
                .returnResult()
                .getResponseBody();
        UUID transactionId = ok(confirmDeposit(user, offer.getOfferToken())).getTransactionId();
        DepositRequested event = (DepositRequested) firstMessage(EventTopics.DEPOSIT_REQUESTED,
                transactionId.toString()).value();
        kafkaTemplate.send(EventTopics.DEPOSIT_COMPLETED, transactionId.toString(),
                new DepositCompleted(transactionId, user));

        // then
        assertThat(offer.getFee()).isEqualByComparingTo("2.00");
        assertThat(offer.getTotal()).isEqualByComparingTo("102.00");
        assertThat(jdbc.queryForObject("SELECT fee FROM transactions WHERE uid = ? AND user_uid = ?",
                BigDecimal.class, transactionId, user)).isEqualByComparingTo("2.00");
        assertThat(event.getAmount()).isEqualByComparingTo("100.00");
        assertThat(event.getTotal()).isEqualByComparingTo("102.00");
        await().atMost(KAFKA_WAIT).until(() -> "COMPLETED".equals(jdbc.queryForObject(
                "SELECT status FROM transactions WHERE uid = ? AND user_uid = ?", String.class, transactionId, user)));
        assertThat(balance(wallet, user)).isEqualByComparingTo("100.00");
    }
}
