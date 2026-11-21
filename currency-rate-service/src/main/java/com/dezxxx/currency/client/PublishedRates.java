package com.dezxxx.currency.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record PublishedRates(Instant publishedAt, Map<String, BigDecimal> usdRates) {
}
