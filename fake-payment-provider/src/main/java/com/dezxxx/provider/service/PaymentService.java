package com.dezxxx.provider.service;

import com.dezxxx.provider.client.PaymentKafkaProducer;
import com.dezxxx.provider.config.ProviderProperties;
import com.dezxxx.provider.metrics.PaymentMetrics;
import com.dezxxx.transaction.event.DepositCompleted;
import com.dezxxx.transaction.event.DepositFailed;
import com.dezxxx.transaction.event.DepositRequested;
import com.dezxxx.transaction.event.EventTopics;
import com.dezxxx.transaction.event.WithdrawalCompleted;
import com.dezxxx.transaction.event.WithdrawalFailed;
import com.dezxxx.transaction.event.WithdrawalRequested;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// stands in for the bank: no card is charged, the answer depends only on the amount
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    static final String NOT_ENOUGH_FUNDS = "Not enough funds on the card";

    static final String BANK_REFUSED = "The bank refused the payout";

    private final ProviderProperties providerProperties;
    private final PaymentKafkaProducer paymentKafkaProducer;
    private final PaymentMetrics paymentMetrics;

    public void processDeposit(DepositRequested request) {
        if (approved(request.getAmount())) {
            log.info("Deposit {} charged from the card", request.getTransactionId());
            paymentKafkaProducer.send(EventTopics.DEPOSIT_COMPLETED, request.getTransactionId(),
                    new DepositCompleted(request.getTransactionId(), request.getUserId()));
            paymentMetrics.answered(PaymentMetrics.DEPOSIT, PaymentMetrics.COMPLETED);
        } else {
            log.info("Deposit {} declined: {}", request.getTransactionId(), NOT_ENOUGH_FUNDS);
            paymentKafkaProducer.send(EventTopics.DEPOSIT_FAILED, request.getTransactionId(),
                    new DepositFailed(request.getTransactionId(), request.getUserId(), NOT_ENOUGH_FUNDS));
            paymentMetrics.answered(PaymentMetrics.DEPOSIT, PaymentMetrics.FAILED);
        }
    }

    public void processWithdrawal(WithdrawalRequested request) {
        if (approved(request.getAmount())) {
            log.info("Withdrawal {} sent to the card", request.getTransactionId());
            paymentKafkaProducer.send(EventTopics.WITHDRAWAL_COMPLETED, request.getTransactionId(),
                    new WithdrawalCompleted(request.getTransactionId(), request.getUserId()));
            paymentMetrics.answered(PaymentMetrics.WITHDRAWAL, PaymentMetrics.COMPLETED);
        } else {
            log.info("Withdrawal {} declined: {}", request.getTransactionId(), BANK_REFUSED);
            paymentKafkaProducer.send(EventTopics.WITHDRAWAL_FAILED, request.getTransactionId(),
                    new WithdrawalFailed(request.getTransactionId(), request.getUserId(), BANK_REFUSED));
            paymentMetrics.answered(PaymentMetrics.WITHDRAWAL, PaymentMetrics.FAILED);
        }
    }

    private boolean approved(BigDecimal amount) {
        return amount.compareTo(providerProperties.approvalLimit()) <= 0;
    }
}
