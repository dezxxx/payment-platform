package com.dezxxx.currency.unit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.currency.api.model.CurrencyResponseDto;
import com.dezxxx.currency.api.model.RateProviderResponseDto;
import com.dezxxx.currency.api.model.RateResponseDto;
import com.dezxxx.currency.entity.ConversionRateEntity;
import com.dezxxx.currency.entity.CurrencyEntity;
import com.dezxxx.currency.entity.RateProviderEntity;
import com.dezxxx.currency.mapper.CurrencyRateMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CurrencyRateMapper")
class CurrencyRateMapperTest {

    private final CurrencyRateMapper mapper = new CurrencyRateMapper();

    @Test
    @DisplayName("given a rate, when mapping, then rate_begin_time becomes rateTimestamp in UTC and the rate keeps every digit")
    void mapsRate() {
        // given
        ConversionRateEntity rate = new ConversionRateEntity();
        rate.setSourceCode("UAH");
        rate.setDestinationCode("EUR");
        rate.setRate(new BigDecimal("0.01994710"));
        rate.setRateBeginTime(Instant.parse("2026-11-14T12:00:00Z"));
        rate.setRateEndTime(Instant.parse("2026-11-14T15:00:00Z"));
        rate.setProviderCode("OXR");

        // when
        RateResponseDto result = mapper.toRateResponse(rate);

        // then
        assertThat(result.getSourceCode()).isEqualTo("UAH");
        assertThat(result.getDestinationCode()).isEqualTo("EUR");
        assertThat(result.getRate()).isEqualTo(new BigDecimal("0.01994710"));
        assertThat(result.getRateTimestamp()).isEqualTo(OffsetDateTime.parse("2026-11-14T12:00:00Z"));
        assertThat(result.getProviderCode()).isEqualTo("OXR");
    }

    @Test
    @DisplayName("given a currency, when mapping, then every field is copied")
    void mapsCurrency() {
        // given
        CurrencyEntity currency = new CurrencyEntity();
        currency.setCode("ILS");
        currency.setIsoCode("376");
        currency.setDescription("Israeli New Shekel");
        currency.setSymbol("₪");
        currency.setActive(true);

        // when
        CurrencyResponseDto result = mapper.toCurrencyResponse(currency);

        // then
        assertThat(result.getCode()).isEqualTo("ILS");
        assertThat(result.getIsoCode()).isEqualTo("376");
        assertThat(result.getDescription()).isEqualTo("Israeli New Shekel");
        assertThat(result.getSymbol()).isEqualTo("₪");
        assertThat(result.getActive()).isTrue();
    }

    @Test
    @DisplayName("given a provider without a description, when mapping, then the description stays empty")
    void mapsProvider() {
        // given
        RateProviderEntity provider = new RateProviderEntity();
        provider.setProviderCode("OXR");
        provider.setProviderName("Open Exchange Rates");
        provider.setPriority(1);
        provider.setActive(false);

        // when
        RateProviderResponseDto result = mapper.toRateProviderResponse(provider);

        // then
        assertThat(result.getProviderCode()).isEqualTo("OXR");
        assertThat(result.getProviderName()).isEqualTo("Open Exchange Rates");
        assertThat(result.getDescription()).isNull();
        assertThat(result.getPriority()).isEqualTo(1);
        assertThat(result.getActive()).isFalse();
    }
}
