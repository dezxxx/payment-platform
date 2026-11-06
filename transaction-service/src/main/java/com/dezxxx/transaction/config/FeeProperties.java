package com.dezxxx.transaction.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("transaction.fee")
public record FeeProperties(BigDecimal deposit, BigDecimal withdrawal, BigDecimal transfer) {
}
