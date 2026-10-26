package com.dezxxx.provider.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

// an amount above the limit is declined: the way to see a *Failed answer on purpose
@ConfigurationProperties("provider")
public record ProviderProperties(BigDecimal approvalLimit) {
}
