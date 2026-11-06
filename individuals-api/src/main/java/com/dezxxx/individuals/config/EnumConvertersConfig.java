package com.dezxxx.individuals.config;

import com.dezxxx.transaction.client.model.TransactionStatusDto;
import com.dezxxx.transaction.client.model.TransactionTypeDto;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

// ?type=deposit: Spring looks enums up by constant name (DEPOSIT), the contract sends the value
@Configuration
public class EnumConvertersConfig implements WebFluxConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, TransactionTypeDto.class, TransactionTypeDto::fromValue);
        registry.addConverter(String.class, TransactionStatusDto.class, TransactionStatusDto::fromValue);
    }
}
