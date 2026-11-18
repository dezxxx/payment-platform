package com.dezxxx.currency.service;

import com.dezxxx.currency.api.model.CurrencyResponseDto;
import com.dezxxx.currency.api.model.RateProviderResponseDto;
import com.dezxxx.currency.api.model.RateResponseDto;
import com.dezxxx.currency.client.ExternalRateProviderClient;
import com.dezxxx.currency.client.PublishedRates;
import com.dezxxx.currency.config.RatesProperties;
import com.dezxxx.currency.entity.ConversionRateEntity;
import com.dezxxx.currency.entity.CurrencyEntity;
import com.dezxxx.currency.entity.RateCorrectionEntity;
import com.dezxxx.currency.entity.RateProviderEntity;
import com.dezxxx.currency.exception.CurrencyRateServiceException;
import com.dezxxx.currency.exception.ErrorCode;
import com.dezxxx.currency.mapper.CurrencyRateMapper;
import com.dezxxx.currency.metrics.RateMetrics;
import com.dezxxx.currency.repository.ConversionRateRepository;
import com.dezxxx.currency.repository.CurrencyRepository;
import com.dezxxx.currency.repository.RateCorrectionRepository;
import com.dezxxx.currency.repository.RateProviderRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExchangeRateService {

    private static final String OPERATIONS = "rate.operations";

    // conversion_rates.rate is numeric(19,8)
    private static final int RATE_SCALE = 8;

    private final ConversionRateRepository conversionRateRepository;
    private final CurrencyRepository currencyRepository;
    private final RateProviderRepository rateProviderRepository;
    private final RateCorrectionRepository rateCorrectionRepository;
    private final ExternalRateProviderClient externalRateProviderClient;
    private final CurrencyRateMapper currencyRateMapper;
    private final PlatformTransactionManager transactionManager;
    private final RatesProperties ratesProperties;
    private final RateMetrics rateMetrics;
    private final Clock clock;

    @Observed(name = OPERATIONS, contextualName = "exchangeRateService.getRate", lowCardinalityKeyValues = {"operation", "getRate"})
    public RateResponseDto getRate(String sourceCode, String destinationCode, OffsetDateTime timestamp) {
        Instant moment = timestamp == null ? clock.instant() : timestamp.toInstant();
        ConversionRateEntity rate = conversionRateRepository.findValid(sourceCode, destinationCode, moment)
                .orElseThrow(() -> new CurrencyRateServiceException(ErrorCode.RATE_NOT_FOUND,
                        "No rate " + sourceCode + " to " + destinationCode + " valid at " + moment));
        log.info("Found rate {} -> {} valid at {}: {}", sourceCode, destinationCode, moment, rate.getRate());
        return currencyRateMapper.toRateResponse(rate);
    }

    public List<CurrencyResponseDto> getCurrencies() {
        return currencyRepository.findAllByCode().stream()
                .map(currencyRateMapper::toCurrencyResponse)
                .toList();
    }

    public List<RateProviderResponseDto> getProviders() {
        return rateProviderRepository.findAllByPriority().stream()
                .map(currencyRateMapper::toRateProviderResponse)
                .toList();
    }

    // no transaction around the provider calls: only the write gets one, limited by transaction-timeout
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void updateRates() {
        Timer.Sample sample = rateMetrics.startUpdate();
        try {
            List<String> currencyCodes = currencyRepository.findAllActive().stream()
                    .map(CurrencyEntity::getCode)
                    .toList();
            Optional<ProviderRates> providerRates = fetchFromFirstValidProvider(currencyCodes);
            if (providerRates.isEmpty()) {
                fail(sample, "no provider gave valid rates", null);
                return;
            }
            ProviderRates rates = providerRates.get();
            if (alreadySaved(rates)) {
                rateMetrics.updateSucceeded(sample);
                log.info("Rates of provider {} published at {} are already saved, nothing to write",
                        rates.providerCode(), rates.publishedAt());
                return;
            }
            List<ConversionRateEntity> pairs = buildPairs(currencyCodes, rates, activeCorrections());
            saveInOneTransaction(pairs);
            rateMetrics.updateSucceeded(sample);
            log.info("Rates updated: {} pairs from provider {} published at {}",
                    pairs.size(), rates.providerCode(), rates.publishedAt());
        } catch (RuntimeException ex) {
            fail(sample, "unexpected error", ex);
        }
    }

    private Optional<ProviderRates> fetchFromFirstValidProvider(List<String> currencyCodes) {
        for (RateProviderEntity provider : rateProviderRepository.findAllActiveByPriority()) {
            String providerCode = provider.getProviderCode();
            try {
                PublishedRates published = externalRateProviderClient.fetchUsdRates(providerCode, currencyCodes);
                if (!coversEveryCurrency(published.usdRates(), currencyCodes)) {
                    log.warn("Rate provider {} missed a currency or gave a rate <= 0, trying the next one", providerCode);
                    continue;
                }
                if (!publishedRecently(published.publishedAt())) {
                    log.warn("Rate provider {} gave rates published at {}: older than {} or in the future, trying the next one",
                            providerCode, published.publishedAt(), ratesProperties.maxAge());
                    continue;
                }
                return Optional.of(new ProviderRates(providerCode, published.publishedAt(), published.usdRates()));
            } catch (RuntimeException ex) {
                log.warn("Rate provider {} failed: {}, trying the next one", providerCode, ex.getMessage());
            }
        }
        return Optional.empty();
    }

    private boolean coversEveryCurrency(Map<String, BigDecimal> usdRates, List<String> currencyCodes) {
        return currencyCodes.stream()
                .map(usdRates::get)
                .allMatch(rate -> rate != null && rate.signum() > 0);
    }

    private boolean publishedRecently(Instant publishedAt) {
        Instant now = clock.instant();
        return !publishedAt.isAfter(now) && !publishedAt.isBefore(now.minus(ratesProperties.maxAge()));
    }

    // a repeated answer must not extend the life of rates the provider published long ago
    private boolean alreadySaved(ProviderRates rates) {
        return conversionRateRepository.existsByProviderCodeAndRateBeginTime(rates.providerCode(), rates.publishedAt());
    }

    private Map<String, BigDecimal> activeCorrections() {
        return rateCorrectionRepository.findAllActive().stream()
                .collect(Collectors.toMap(
                        correction -> pairKey(correction.getSourceCode(), correction.getDestinationCode()),
                        RateCorrectionEntity::getCoefficient));
    }

    private List<ConversionRateEntity> buildPairs(List<String> currencyCodes, ProviderRates providerRates,
                                                  Map<String, BigDecimal> corrections) {
        Instant begin = providerRates.publishedAt();
        Instant end = begin.plus(ratesProperties.lifetime());

        List<ConversionRateEntity> rates = new ArrayList<>();
        for (String source : currencyCodes) {
            for (String destination : currencyCodes) {
                if (source.equals(destination)) {
                    continue;
                }
                BigDecimal coefficient = corrections.getOrDefault(pairKey(source, destination), BigDecimal.ONE);
                BigDecimal rate = crossRate(providerRates.usdRates(), source, destination, coefficient);
                rates.add(newRate(source, destination, rate, begin, end, providerRates.providerCode()));
            }
        }
        return rates;
    }

    // every rate is to USD: source -> destination = (USD -> destination) / (USD -> source) x the pair coefficient,
    // rounded once, at the end
    private BigDecimal crossRate(Map<String, BigDecimal> usdRates, String source, String destination,
                                 BigDecimal coefficient) {
        return usdRates.get(destination)
                .divide(usdRates.get(source), MathContext.DECIMAL64)
                .multiply(coefficient)
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static String pairKey(String source, String destination) {
        return source + "/" + destination;
    }

    private ConversionRateEntity newRate(String source, String destination, BigDecimal rate,
                                         Instant begin, Instant end, String providerCode) {
        ConversionRateEntity entity = new ConversionRateEntity();
        entity.setSourceCode(source);
        entity.setDestinationCode(destination);
        entity.setRate(rate);
        entity.setRateBeginTime(begin);
        entity.setRateEndTime(end);
        entity.setProviderCode(providerCode);
        return entity;
    }

    private void saveInOneTransaction(List<ConversionRateEntity> rates) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout((int) ratesProperties.transactionTimeout().toSeconds());
        transaction.executeWithoutResult(status -> conversionRateRepository.saveAll(rates));
    }

    private void fail(Timer.Sample sample, String reason, Exception ex) {
        rateMetrics.updateFailed(sample);
        log.error("Rate update failed: {}; the previous rates stay valid until their rate_end_time", reason, ex);
    }

    private record ProviderRates(String providerCode, Instant publishedAt, Map<String, BigDecimal> usdRates) {
    }
}
