package com.dezxxx.transaction.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("transaction.transfer")
public record TransferProperties(BigDecimal minConversionAmountUsd) {
}
