package com.dezxxx.provider.client;

import com.dezxxx.provider.service.PaymentService;
import com.dezxxx.transaction.event.DepositRequested;
import com.dezxxx.transaction.event.EventTopics;
import com.dezxxx.transaction.event.WithdrawalRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// the requests of transaction-service; the method is chosen by the event class.
// Each one runs in a Kafka transaction: read, answer and offset commit together (Kafka to Kafka exactly-once)
@Component
@RequiredArgsConstructor
@KafkaListener(topics = {EventTopics.DEPOSIT_REQUESTED, EventTopics.WITHDRAWAL_REQUESTED})
public class PaymentKafkaConsumer {

    private final PaymentService paymentService;

    @KafkaHandler
    public void onDepositRequested(DepositRequested request) {
        paymentService.processDeposit(request);
    }

    @KafkaHandler
    public void onWithdrawalRequested(WithdrawalRequested request) {
        paymentService.processWithdrawal(request);
    }
}
