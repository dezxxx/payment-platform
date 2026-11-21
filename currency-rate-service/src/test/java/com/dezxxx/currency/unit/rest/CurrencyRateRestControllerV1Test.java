package com.dezxxx.currency.unit.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dezxxx.currency.api.model.CurrencyResponseDto;
import com.dezxxx.currency.api.model.RateProviderResponseDto;
import com.dezxxx.currency.api.model.RateResponseDto;
import com.dezxxx.currency.exception.CurrencyRateServiceException;
import com.dezxxx.currency.exception.ErrorCode;
import com.dezxxx.currency.rest.CurrencyRateRestControllerV1;
import com.dezxxx.currency.service.ExchangeRateService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
@DisplayName("CurrencyRateRestControllerV1")
class CurrencyRateRestControllerV1Test {

    @Mock
    private ExchangeRateService exchangeRateService;

    private CurrencyRateRestControllerV1 controller;

    @BeforeEach
    void setUp() {
        controller = new CurrencyRateRestControllerV1(exchangeRateService);
    }

    @Test
    @DisplayName("given from equals to, when getting a rate, then VALIDATION_ERROR and the service is not called")
    void refusesSameCurrency() {
        // when, then
        assertThatThrownBy(() -> controller.getRate("USD", "USD", null))
                .isInstanceOf(CurrencyRateServiceException.class)
                .hasMessage("from equals to")
                .extracting(ex -> ((CurrencyRateServiceException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(exchangeRateService, never()).getRate(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("given two different currencies, when getting a rate, then 200 with the service's rate")
    void returnsRate() {
        // given
        RateResponseDto rate = new RateResponseDto().sourceCode("UAH").destinationCode("EUR");
        when(exchangeRateService.getRate("UAH", "EUR", null)).thenReturn(rate);

        // when
        ResponseEntity<RateResponseDto> result = controller.getRate("UAH", "EUR", null);

        // then
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(rate);
    }

    @Test
    @DisplayName("when listing currencies and providers, then 200 with the service's lists")
    void returnsLists() {
        // given
        List<CurrencyResponseDto> currencies = List.of(new CurrencyResponseDto().code("EUR"));
        List<RateProviderResponseDto> providers = List.of(new RateProviderResponseDto().providerCode("OXR"));
        when(exchangeRateService.getCurrencies()).thenReturn(currencies);
        when(exchangeRateService.getProviders()).thenReturn(providers);

        // when
        ResponseEntity<List<CurrencyResponseDto>> currencyResult = controller.getCurrencies();
        ResponseEntity<List<RateProviderResponseDto>> providerResult = controller.getRateProviders();

        // then
        assertThat(currencyResult.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(currencyResult.getBody()).isSameAs(currencies);
        assertThat(providerResult.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(providerResult.getBody()).isSameAs(providers);
    }
}
