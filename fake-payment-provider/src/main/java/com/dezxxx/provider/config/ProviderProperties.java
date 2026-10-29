package com.dezxxx.provider.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("provider")
public record ProviderProperties(BigDecimal approvalLimit) {
}
