package com.dezxxx.currency.unit.scheduler;

import static org.mockito.Mockito.verify;

import com.dezxxx.currency.scheduler.RateUpdateScheduler;
import com.dezxxx.currency.service.ExchangeRateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateUpdateScheduler")
class RateUpdateSchedulerTest {

    @Mock
    private ExchangeRateService exchangeRateService;

    @Test
    @DisplayName("when the schedule fires, then the service updates the rates")
    void updatesRates() {
        // when
        new RateUpdateScheduler(exchangeRateService).updateRates();

        // then
        verify(exchangeRateService).updateRates();
    }
}
