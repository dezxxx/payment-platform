package com.dezxxx.currency.rest;

import com.dezxxx.currency.api.CurrencyRateApi;
import com.dezxxx.currency.api.model.CurrencyResponseDto;
import com.dezxxx.currency.api.model.RateProviderResponseDto;
import com.dezxxx.currency.api.model.RateResponseDto;
import com.dezxxx.currency.exception.CurrencyRateServiceException;
import com.dezxxx.currency.exception.ErrorCode;
import com.dezxxx.currency.service.ExchangeRateService;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CurrencyRateRestControllerV1 implements CurrencyRateApi {

    private final ExchangeRateService exchangeRateService;

    @Override
    public ResponseEntity<RateResponseDto> getRate(String from, String to, OffsetDateTime timestamp) {
        if (from.equals(to)) {
            throw new CurrencyRateServiceException(ErrorCode.VALIDATION_ERROR, "from equals to");
        }
        return ResponseEntity.ok(exchangeRateService.getRate(from, to, timestamp));
    }

    @Override
    public ResponseEntity<List<CurrencyResponseDto>> getCurrencies() {
        return ResponseEntity.ok(exchangeRateService.getCurrencies());
    }

    @Override
    public ResponseEntity<List<RateProviderResponseDto>> getRateProviders() {
        return ResponseEntity.ok(exchangeRateService.getProviders());
    }
}
