package com.dezxxx.provider.config;

import com.dezxxx.transaction.event.TrustedEvents;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
public class KafkaConfig {

    static {
        TrustedEvents.trust();
    }
}
