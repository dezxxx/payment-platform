package com.dezxxx.provider.unit.client;

import static org.mockito.Mockito.verify;

import com.dezxxx.provider.client.PaymentKafkaConsumer;
import com.dezxxx.provider.service.PaymentService;
import com.dezxxx.transaction.event.DepositRequested;
import com.dezxxx.transaction.event.WithdrawalRequested;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentKafkaConsumer")
class PaymentKafkaConsumerTest {

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private PaymentKafkaConsumer consumer;

    @Test
    @DisplayName("given DepositRequested, when it arrives, then the deposit is processed")
    void processesDeposit() {
        // given
        DepositRequested request = new DepositRequested(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("1.00"), "USD");

        // when
        consumer.onDepositRequested(request);

        // then
        verify(paymentService).processDeposit(request);
    }

    @Test
    @DisplayName("given WithdrawalRequested, when it arrives, then the withdrawal is processed")
    void processesWithdrawal() {
        // given
        WithdrawalRequested request = new WithdrawalRequested(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("1.00"), "USD");

        // when
        consumer.onWithdrawalRequested(request);

        // then
        verify(paymentService).processWithdrawal(request);
    }
}
