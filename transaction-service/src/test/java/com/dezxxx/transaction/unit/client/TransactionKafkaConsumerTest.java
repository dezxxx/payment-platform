package com.dezxxx.transaction.unit.client;

import static org.mockito.Mockito.verify;

import com.dezxxx.transaction.client.TransactionKafkaConsumer;
import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import com.dezxxx.transaction.event.DepositCompleted;
import com.dezxxx.transaction.event.DepositFailed;
import com.dezxxx.transaction.event.WithdrawalCompleted;
import com.dezxxx.transaction.event.WithdrawalFailed;
import com.dezxxx.transaction.service.TransactionService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionKafkaConsumer")
class TransactionKafkaConsumerTest {

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    private static final UUID TRANSACTION_UID = UUID.fromString("9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

    @Mock
    private TransactionService transactionService;

    @InjectMocks
    private TransactionKafkaConsumer consumer;

    @Test
    @DisplayName("given DepositCompleted, when it arrives, then the transaction is finalized as COMPLETED in the user's shard")
    void completesDeposit() {
        // when
        consumer.onDepositCompleted(new DepositCompleted(TRANSACTION_UID, USER_UID));

        // then
        verify(transactionService).finalizeTransaction(TRANSACTION_UID, USER_UID, TransactionStatus.COMPLETED, null);
    }

    @Test
    @DisplayName("given DepositFailed, when it arrives, then FAILED with the provider's reason")
    void failsDeposit() {
        // when
        consumer.onDepositFailed(new DepositFailed(TRANSACTION_UID, USER_UID, "Bank refused"));

        // then
        verify(transactionService).finalizeTransaction(TRANSACTION_UID, USER_UID, TransactionStatus.FAILED,
                "Bank refused");
    }

    @Test
    @DisplayName("given WithdrawalCompleted, when it arrives, then COMPLETED without a reason")
    void completesWithdrawal() {
        // when
        consumer.onWithdrawalCompleted(new WithdrawalCompleted(TRANSACTION_UID, USER_UID));

        // then
        verify(transactionService).finalizeTransaction(TRANSACTION_UID, USER_UID, TransactionStatus.COMPLETED, null);
    }

    @Test
    @DisplayName("given WithdrawalFailed, when it arrives, then FAILED with the provider's reason")
    void failsWithdrawal() {
        // when
        consumer.onWithdrawalFailed(new WithdrawalFailed(TRANSACTION_UID, USER_UID, "Bank refused"));

        // then
        verify(transactionService).finalizeTransaction(TRANSACTION_UID, USER_UID, TransactionStatus.FAILED,
                "Bank refused");
    }
}
