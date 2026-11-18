package com.dezxxx.currency.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.dezxxx.currency.api.model.CurrencyResponseDto;
import com.dezxxx.currency.api.model.RateProviderResponseDto;
import com.dezxxx.currency.api.model.RateResponseDto;
import com.dezxxx.currency.client.ExternalRateProviderClient;
import com.dezxxx.currency.config.RatesProperties;
import com.dezxxx.currency.entity.ConversionRateEntity;
import com.dezxxx.currency.entity.CurrencyEntity;
import com.dezxxx.currency.entity.RateProviderEntity;
import com.dezxxx.currency.exception.CurrencyRateServiceException;
import com.dezxxx.currency.exception.ErrorCode;
import com.dezxxx.currency.mapper.CurrencyRateMapper;
import com.dezxxx.currency.metrics.RateMetrics;
import com.dezxxx.currency.repository.ConversionRateRepository;
import com.dezxxx.currency.repository.CurrencyRepository;
import com.dezxxx.currency.repository.RateCorrectionRepository;
import com.dezxxx.currency.repository.RateProviderRepository;
import com.dezxxx.currency.service.ExchangeRateService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExchangeRateService: reading")
class ExchangeRateServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:30:00Z");

    private static final Instant BEGIN = Instant.parse("2026-11-14T12:00:00Z");

    @Mock
    private ConversionRateRepository conversionRateRepository;

    @Mock
    private CurrencyRepository currencyRepository;

    @Mock
    private RateProviderRepository rateProviderRepository;

    @Mock
    private RateCorrectionRepository rateCorrectionRepository;

    @Mock
    private ExternalRateProviderClient externalRateProviderClient;

    @Mock
    private PlatformTransactionManager transactionManager;

    private ExchangeRateService service;

    @BeforeEach
    void setUp() {
        RatesProperties properties = new RatesProperties(Duration.ofHours(1), Duration.ZERO, Duration.ofHours(3),
                Duration.ofMinutes(59), Duration.ofMinutes(59), Duration.ofSeconds(30), Duration.ofHours(2));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RateMetrics metrics = new RateMetrics(new SimpleMeterRegistry(), conversionRateRepository, clock);
        service = new ExchangeRateService(conversionRateRepository, currencyRepository, rateProviderRepository,
                rateCorrectionRepository, externalRateProviderClient, new CurrencyRateMapper(), transactionManager, properties, metrics, clock);
    }

    @Test
    @DisplayName("given no timestamp, when getting a rate, then the rate valid now is returned with its begin as rateTimestamp")
    void returnsRateValidNow() {
        // given
        when(conversionRateRepository.findValid("UAH", "EUR", NOW)).thenReturn(Optional.of(rate()));

        // when
        RateResponseDto result = service.getRate("UAH", "EUR", null);

        // then
        assertThat(result.getSourceCode()).isEqualTo("UAH");
        assertThat(result.getDestinationCode()).isEqualTo("EUR");
        assertThat(result.getRate()).isEqualByComparingTo("0.01994710");
        assertThat(result.getRateTimestamp()).isEqualTo(BEGIN.atOffset(ZoneOffset.UTC));
        assertThat(result.getProviderCode()).isEqualTo("OXR");
    }

    @Test
    @DisplayName("given a timestamp with an offset, when getting a rate, then the same moment in UTC is looked up")
    void looksUpTimestampInUtc() {
        // given
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-11-14T12:15:00+02:00");
        when(conversionRateRepository.findValid("UAH", "EUR", Instant.parse("2026-11-14T10:15:00Z")))
                .thenReturn(Optional.of(rate()));

        // when
        RateResponseDto result = service.getRate("UAH", "EUR", timestamp);

        // then
        assertThat(result.getRate()).isEqualByComparingTo("0.01994710");
    }

    @Test
    @DisplayName("given no valid rate, when getting a rate, then RATE_NOT_FOUND names the pair and the moment")
    void refusesMissingRate() {
        // given
        when(conversionRateRepository.findValid("UAH", "EUR", NOW)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> service.getRate("UAH", "EUR", null))
                .isInstanceOf(CurrencyRateServiceException.class)
                .hasMessage("No rate UAH to EUR valid at 2026-11-14T12:30:00Z")
                .extracting(ex -> ((CurrencyRateServiceException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RATE_NOT_FOUND);
    }

    @Test
    @DisplayName("given currencies, when listing them, then every currency is mapped in the repository order")
    void listsCurrencies() {
        // given
        when(currencyRepository.findAllByCode()).thenReturn(List.of(currency("EUR", true), currency("UAH", false)));

        // when
        List<CurrencyResponseDto> result = service.getCurrencies();

        // then
        assertThat(result).extracting(CurrencyResponseDto::getCode).containsExactly("EUR", "UAH");
        assertThat(result).extracting(CurrencyResponseDto::getActive).containsExactly(true, false);
    }

    @Test
    @DisplayName("given providers, when listing them, then every provider is mapped in the repository order")
    void listsProviders() {
        // given
        RateProviderEntity provider = new RateProviderEntity();
        provider.setProviderCode("OXR");
        provider.setProviderName("Open Exchange Rates");
        provider.setPriority(1);
        provider.setActive(true);
        when(rateProviderRepository.findAllByPriority()).thenReturn(List.of(provider));

        // when
        List<RateProviderResponseDto> result = service.getProviders();

        // then
        assertThat(result).extracting(RateProviderResponseDto::getProviderCode).containsExactly("OXR");
        assertThat(result).extracting(RateProviderResponseDto::getPriority).containsExactly(1);
    }

    private static ConversionRateEntity rate() {
        ConversionRateEntity rate = new ConversionRateEntity();
        rate.setSourceCode("UAH");
        rate.setDestinationCode("EUR");
        rate.setRate(new BigDecimal("0.01994710"));
        rate.setRateBeginTime(BEGIN);
        rate.setRateEndTime(BEGIN.plus(Duration.ofHours(3)));
        rate.setProviderCode("OXR");
        return rate;
    }

    private static CurrencyEntity currency(String code, boolean active) {
        CurrencyEntity currency = new CurrencyEntity();
        currency.setCode(code);
        currency.setActive(active);
        return currency;
    }
}
