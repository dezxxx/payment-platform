package com.dezxxx.currency.unit.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dezxxx.currency.client.ExternalRateProviderClient;
import com.dezxxx.currency.client.OxrApi;
import com.dezxxx.currency.client.OxrLatestResponse;
import com.dezxxx.currency.client.PublishedRates;
import com.dezxxx.currency.exception.CurrencyRateServiceException;
import com.dezxxx.currency.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExternalRateProviderClient")
class ExternalRateProviderClientTest {

    @Mock
    private OxrApi oxrApi;

    private ExternalRateProviderClient client;

    @BeforeEach
    void setUp() {
        client = new ExternalRateProviderClient(oxrApi);
    }

    @Test
    @DisplayName("given OXR, when fetching, then only our currencies are asked for and the USD rates come with the publication time")
    void fetchesOnlyOurCurrencies() {
        // given
        Map<String, BigDecimal> rates = Map.of("USD", BigDecimal.ONE, "EUR", new BigDecimal("0.90715"));
        when(oxrApi.latest("USD,EUR")).thenReturn(new OxrLatestResponse(1794682806L, "USD", rates));

        // when
        PublishedRates result = client.fetchUsdRates("OXR", List.of("USD", "EUR"));

        // then
        assertThat(result.usdRates()).isEqualTo(rates);
        assertThat(result.publishedAt()).isEqualTo(Instant.parse("2026-11-14T19:00:06Z"));
    }

    @Test
    @DisplayName("given a provider without a client, when fetching, then INTERNAL_ERROR and nothing is called")
    void refusesUnknownProvider() {
        // when, then
        assertThatThrownBy(() -> client.fetchUsdRates("FXR", List.of("USD")))
                .isInstanceOf(CurrencyRateServiceException.class)
                .hasMessage("No client for rate provider FXR")
                .extracting(ex -> ((CurrencyRateServiceException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        verify(oxrApi, never()).latest(anyString());
    }
}
