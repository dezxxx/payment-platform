package com.dezxxx.currency.client;

import java.math.BigDecimal;
import java.util.Map;

public record OxrLatestResponse(long timestamp, String base, Map<String, BigDecimal> rates) {
}
