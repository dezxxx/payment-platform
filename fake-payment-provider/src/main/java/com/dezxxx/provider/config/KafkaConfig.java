package com.dezxxx.provider.config;

import com.dezxxx.transaction.event.TrustedEvents;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// the topics are created by transaction-service, the owner of the contract
@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
public class KafkaConfig {

    static {
        TrustedEvents.trust();
    }
}
