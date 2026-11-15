package com.dezxxx.currency.client;

import com.dezxxx.currency.exception.CurrencyRateServiceException;
import com.dezxxx.currency.exception.ErrorCode;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalRateProviderClient {

    private static final String OXR = "OXR";

    private final OxrApi oxrApi;

    @Retry(name = "rateProvider")
    public Map<String, BigDecimal> fetchUsdRates(String providerCode, Collection<String> currencyCodes) {
        if (!OXR.equals(providerCode)) {
            throw new CurrencyRateServiceException(ErrorCode.INTERNAL_ERROR, "No client for rate provider " + providerCode);
        }
        OxrLatestResponse response = oxrApi.latest(String.join(",", currencyCodes));
        log.info("Rate provider {} answered {} rates, base {}", providerCode, response.rates().size(), response.base());
        return response.rates();
    }
}
