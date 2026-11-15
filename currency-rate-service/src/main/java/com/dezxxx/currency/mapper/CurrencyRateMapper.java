package com.dezxxx.currency.mapper;

import com.dezxxx.currency.api.model.CurrencyResponseDto;
import com.dezxxx.currency.api.model.RateProviderResponseDto;
import com.dezxxx.currency.api.model.RateResponseDto;
import com.dezxxx.currency.entity.ConversionRateEntity;
import com.dezxxx.currency.entity.CurrencyEntity;
import com.dezxxx.currency.entity.RateProviderEntity;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

@Component
public class CurrencyRateMapper {

    public RateResponseDto toRateResponse(ConversionRateEntity rate) {
        return new RateResponseDto()
                .sourceCode(rate.getSourceCode())
                .destinationCode(rate.getDestinationCode())
                .rate(rate.getRate())
                .rateTimestamp(rate.getRateBeginTime().atOffset(ZoneOffset.UTC))
                .providerCode(rate.getProviderCode());
    }

    public CurrencyResponseDto toCurrencyResponse(CurrencyEntity currency) {
        return new CurrencyResponseDto()
                .code(currency.getCode())
                .isoCode(currency.getIsoCode())
                .description(currency.getDescription())
                .symbol(currency.getSymbol())
                .active(currency.isActive());
    }

    public RateProviderResponseDto toRateProviderResponse(RateProviderEntity provider) {
        return new RateProviderResponseDto()
                .providerCode(provider.getProviderCode())
                .providerName(provider.getProviderName())
                .description(provider.getDescription())
                .priority(provider.getPriority())
                .active(provider.isActive());
    }
}
