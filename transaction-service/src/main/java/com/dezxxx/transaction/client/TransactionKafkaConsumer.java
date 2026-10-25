package com.dezxxx.transaction.client;

import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import com.dezxxx.transaction.event.DepositCompleted;
import com.dezxxx.transaction.event.DepositFailed;
import com.dezxxx.transaction.event.EventTopics;
import com.dezxxx.transaction.event.WithdrawalCompleted;
import com.dezxxx.transaction.event.WithdrawalFailed;
import com.dezxxx.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// the provider's answers; the method is chosen by the event class. userId from the event sends
// ShardingSphere to one shard. An exception goes to the error handler: retries, then the DLT
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(topics = {EventTopics.DEPOSIT_COMPLETED, EventTopics.DEPOSIT_FAILED,
        EventTopics.WITHDRAWAL_COMPLETED, EventTopics.WITHDRAWAL_FAILED})
public class TransactionKafkaConsumer {

    private final TransactionService transactionService;

    @KafkaHandler
    public void onDepositCompleted(DepositCompleted event) {
        log.info("DepositCompleted for transaction {}", event.getTransactionId());
        transactionService.finalizeTransaction(event.getTransactionId(), event.getUserId(),
                TransactionStatus.COMPLETED, null);
    }

    @KafkaHandler
    public void onDepositFailed(DepositFailed event) {
        log.info("DepositFailed for transaction {}: {}", event.getTransactionId(), event.getFailureReason());
        transactionService.finalizeTransaction(event.getTransactionId(), event.getUserId(),
                TransactionStatus.FAILED, event.getFailureReason());
    }

    @KafkaHandler
    public void onWithdrawalCompleted(WithdrawalCompleted event) {
        log.info("WithdrawalCompleted for transaction {}", event.getTransactionId());
        transactionService.finalizeTransaction(event.getTransactionId(), event.getUserId(),
                TransactionStatus.COMPLETED, null);
    }

    @KafkaHandler
    public void onWithdrawalFailed(WithdrawalFailed event) {
        log.info("WithdrawalFailed for transaction {}: {}", event.getTransactionId(), event.getFailureReason());
        transactionService.finalizeTransaction(event.getTransactionId(), event.getUserId(),
                TransactionStatus.FAILED, event.getFailureReason());
    }
}
