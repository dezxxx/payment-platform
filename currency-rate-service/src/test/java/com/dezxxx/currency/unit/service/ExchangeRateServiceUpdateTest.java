package com.dezxxx.currency.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dezxxx.currency.client.ExternalRateProviderClient;
import com.dezxxx.currency.config.RatesProperties;
import com.dezxxx.currency.entity.ConversionRateEntity;
import com.dezxxx.currency.entity.CurrencyEntity;
import com.dezxxx.currency.entity.RateProviderEntity;
import com.dezxxx.currency.mapper.CurrencyRateMapper;
import com.dezxxx.currency.metrics.RateMetrics;
import com.dezxxx.currency.repository.ConversionRateRepository;
import com.dezxxx.currency.repository.CurrencyRepository;
import com.dezxxx.currency.repository.RateProviderRepository;
import com.dezxxx.currency.service.ExchangeRateService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExchangeRateService: hourly update")
class ExchangeRateServiceUpdateTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    private static final List<String> CODES = List.of("USD", "EUR", "UAH", "ILS");

    // the answer of Open Exchange Rates on 2026-11-14
    private static final Map<String, BigDecimal> OXR_RATES = Map.of(
            "USD", BigDecimal.ONE,
            "EUR", new BigDecimal("0.90715"),
            "UAH", new BigDecimal("45.477786"),
            "ILS", new BigDecimal("3.216745"));

    @Mock
    private ConversionRateRepository conversionRateRepository;

    @Mock
    private CurrencyRepository currencyRepository;

    @Mock
    private RateProviderRepository rateProviderRepository;

    @Mock
    private ExternalRateProviderClient externalRateProviderClient;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Captor
    private ArgumentCaptor<List<ConversionRateEntity>> saved;

    private MeterRegistry registry;

    private ExchangeRateService service;

    @BeforeEach
    void setUp() {
        RatesProperties properties = new RatesProperties(Duration.ofHours(1), Duration.ZERO, Duration.ofHours(3),
                Duration.ofMinutes(59), Duration.ofMinutes(59), Duration.ofSeconds(30));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        registry = new SimpleMeterRegistry();
        RateMetrics metrics = new RateMetrics(registry, conversionRateRepository, clock);
        service = new ExchangeRateService(conversionRateRepository, currencyRepository, rateProviderRepository,
                externalRateProviderClient, new CurrencyRateMapper(), transactionManager, properties, metrics, clock);
        when(currencyRepository.findAllActive()).thenReturn(CODES.stream().map(ExchangeRateServiceUpdateTest::currency).toList());
    }

    @Test
    @DisplayName("given valid OXR rates, when updating, then 12 pairs are saved through USD, valid 3 hours")
    void savesEveryPairThroughUsd() {
        // given
        when(rateProviderRepository.findAllActiveByPriority()).thenReturn(List.of(provider("OXR")));
        when(externalRateProviderClient.fetchUsdRates("OXR", CODES)).thenReturn(OXR_RATES);

        // when
        service.updateRates();

        // then
        verify(conversionRateRepository).saveAll(saved.capture());
        List<ConversionRateEntity> rates = saved.getValue();
        assertThat(rates).hasSize(12);
        assertThat(rates).noneMatch(rate -> rate.getSourceCode().equals(rate.getDestinationCode()));
        assertThat(rates).allMatch(rate -> rate.getRateBeginTime().equals(NOW)
                && rate.getRateEndTime().equals(NOW.plus(Duration.ofHours(3)))
                && rate.getProviderCode().equals("OXR"));
        assertThat(rateOf(rates, "UAH", "EUR")).isEqualByComparingTo("0.01994710");
        assertThat(rateOf(rates, "EUR", "UAH")).isEqualByComparingTo("50.13259770");
        assertThat(rateOf(rates, "USD", "EUR")).isEqualByComparingTo("0.90715000");
        assertThat(rateOf(rates, "EUR", "USD")).isEqualByComparingTo("1.10235352");
        assertThat(rateOf(rates, "ILS", "UAH")).isEqualByComparingTo("14.13782752");
        assertThat(updates("success")).isEqualTo(1);
        assertThat(updates("failure")).isZero();
    }

    @Test
    @DisplayName("given the first provider misses a currency, when updating, then the next provider's rates are saved")
    void skipsProviderMissingCurrency() {
        // given
        when(rateProviderRepository.findAllActiveByPriority()).thenReturn(List.of(provider("OXR"), provider("FXR")));
        when(externalRateProviderClient.fetchUsdRates("OXR", CODES))
                .thenReturn(Map.of("USD", BigDecimal.ONE, "EUR", new BigDecimal("0.90715")));
        when(externalRateProviderClient.fetchUsdRates("FXR", CODES)).thenReturn(OXR_RATES);

        // when
        service.updateRates();

        // then
        verify(conversionRateRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(12).allMatch(rate -> rate.getProviderCode().equals("FXR"));
    }

    @Test
    @DisplayName("given the first provider gives a rate of zero, when updating, then the next provider's rates are saved")
    void skipsProviderWithZeroRate() {
        // given
        when(rateProviderRepository.findAllActiveByPriority()).thenReturn(List.of(provider("OXR"), provider("FXR")));
        when(externalRateProviderClient.fetchUsdRates("OXR", CODES)).thenReturn(Map.of(
                "USD", BigDecimal.ONE, "EUR", BigDecimal.ZERO, "UAH", new BigDecimal("45.477786"),
                "ILS", new BigDecimal("3.216745")));
        when(externalRateProviderClient.fetchUsdRates("FXR", CODES)).thenReturn(OXR_RATES);

        // when
        service.updateRates();

        // then
        verify(conversionRateRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).allMatch(rate -> rate.getProviderCode().equals("FXR"));
    }

    @Test
    @DisplayName("given the first provider is down, when updating, then the next provider's rates are saved")
    void skipsFailedProvider() {
        // given
        when(rateProviderRepository.findAllActiveByPriority()).thenReturn(List.of(provider("OXR"), provider("FXR")));
        when(externalRateProviderClient.fetchUsdRates("OXR", CODES))
                .thenThrow(new ResourceAccessException("Connect timed out"));
        when(externalRateProviderClient.fetchUsdRates("FXR", CODES)).thenReturn(OXR_RATES);

        // when
        service.updateRates();

        // then
        verify(conversionRateRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).allMatch(rate -> rate.getProviderCode().equals("FXR"));
        assertThat(updates("success")).isEqualTo(1);
    }

    @Test
    @DisplayName("given no provider gives valid rates, when updating, then nothing is saved and the failure is counted")
    void savesNothingWithoutValidProvider() {
        // given
        when(rateProviderRepository.findAllActiveByPriority()).thenReturn(List.of(provider("OXR")));
        when(externalRateProviderClient.fetchUsdRates("OXR", CODES))
                .thenThrow(new ResourceAccessException("Connect timed out"));

        // when
        service.updateRates();

        // then
        verify(conversionRateRepository, never()).saveAll(anyList());
        assertThat(updates("failure")).isEqualTo(1);
        assertThat(updates("success")).isZero();
    }

    @Test
    @DisplayName("given the write fails, when updating, then the failure is counted and nothing is thrown to the scheduler")
    void countsFailedWrite() {
        // given
        when(rateProviderRepository.findAllActiveByPriority()).thenReturn(List.of(provider("OXR")));
        when(externalRateProviderClient.fetchUsdRates("OXR", CODES)).thenReturn(OXR_RATES);
        when(conversionRateRepository.saveAll(anyList()))
                .thenThrow(new DataIntegrityViolationException("ck_conversion_rates_rate_positive"));

        // when
        service.updateRates();

        // then
        assertThat(updates("failure")).isEqualTo(1);
        assertThat(updates("success")).isZero();
    }

    private double updates(String result) {
        return registry.get("rate.updates").tag("result", result).counter().count();
    }

    private static BigDecimal rateOf(List<ConversionRateEntity> rates, String source, String destination) {
        return rates.stream()
                .filter(rate -> rate.getSourceCode().equals(source) && rate.getDestinationCode().equals(destination))
                .findFirst()
                .orElseThrow()
                .getRate();
    }

    private static CurrencyEntity currency(String code) {
        CurrencyEntity currency = new CurrencyEntity();
        currency.setCode(code);
        currency.setActive(true);
        return currency;
    }

    private static RateProviderEntity provider(String code) {
        RateProviderEntity provider = new RateProviderEntity();
        provider.setProviderCode(code);
        provider.setActive(true);
        return provider;
    }
}
