package com.dezxxx.individuals.gateway.currency;

import com.dezxxx.currency.client.api.CurrencyRateApi;
import com.dezxxx.currency.client.model.RateResponseDto;
import com.dezxxx.individuals.config.CurrencyRateServiceProperties;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.individuals.util.GatewayErrors;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class CurrencyRateServiceClient {

    private static final String CURRENCY_RATE_SERVICE = "currency-rate-service";

    private final CurrencyRateApi currencyRateApi;
    private final CurrencyRateServiceProperties properties;
    private final AuthMetrics metrics;
    private final CircuitBreakerRegistry breakers;

    public Mono<BigDecimal> getRate(String sourceCode, String destinationCode) {
        return metrics.timeCurrencyRateService(Mono.defer(() -> currencyRateApi.getRate(sourceCode, destinationCode, null)))
                .transformDeferred(CircuitBreakerOperator.of(breakers.circuitBreaker(CURRENCY_RATE_SERVICE)))
                .map(CurrencyRateServiceClient::rateOf)
                .onErrorMap(WebClientResponseException.class, CurrencyRateServiceClient::translate)
                .transform(GatewayErrors.transportFailures(CURRENCY_RATE_SERVICE, properties.baseUrl()));
    }

    private static BigDecimal rateOf(ResponseEntity<RateResponseDto> response) {
        RateResponseDto body = response.getBody();
        if (body == null || body.getRate() == null) {
            log.error("currency-rate-service answered {} without a rate", response.getStatusCode());
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        return body.getRate();
    }

    private static ApiException translate(WebClientResponseException ex) {
        if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
            log.info("currency-rate-service has no valid rate: {}", ex.getResponseBodyAsString());
            return new ApiException(ErrorCode.RATE_NOT_FOUND);
        }
        if (ex.getStatusCode().is5xxServerError()) {
            log.warn("currency-rate-service answered {}: {}", ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        // any other 4xx means our request is wrong - our bug, not the caller's; the body goes to the log only
        log.error("currency-rate-service answered {} to our request: {}",
                ex.getStatusCode().value(), ex.getResponseBodyAsString());
        return new ApiException(ErrorCode.INTERNAL_ERROR);
    }
}
