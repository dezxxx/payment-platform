package com.dezxxx.transaction.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

// a share of the amount per operation: 0.01 is 1 %
@ConfigurationProperties("transaction.fee")
public record FeeProperties(BigDecimal deposit, BigDecimal withdrawal, BigDecimal transfer) {
}
