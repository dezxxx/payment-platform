package com.dezxxx.transaction.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("transaction.kafka")
public record KafkaEventProperties(int partitions, Duration sendInterval, int batchSize,
                                   Duration retryInterval, int retryAttempts) {
}
