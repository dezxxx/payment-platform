package com.dezxxx.currency.scheduler;

import com.dezxxx.currency.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RateUpdateScheduler {

    private final ExchangeRateService exchangeRateService;

    @Scheduled(initialDelayString = "${currency.rates.initial-delay}", fixedRateString = "${currency.rates.update-interval}")
    @SchedulerLock(name = "updateRates",
            lockAtMostFor = "${currency.rates.lock-at-most-for}",
            lockAtLeastFor = "${currency.rates.lock-at-least-for}")
    public void updateRates() {
        exchangeRateService.updateRates();
    }
}
