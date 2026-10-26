package com.dezxxx.transaction.config;

import com.dezxxx.transaction.event.EventTopics;
import com.dezxxx.transaction.event.TrustedEvents;
import com.dezxxx.transaction.metrics.TransactionMetrics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

// the topics of the Kafka contract, and what happens to a message the consumer cannot process.
// Scheduling is on for TransactionKafkaProducer and TransactionMetrics
@Configuration
@EnableScheduling
@EnableConfigurationProperties(KafkaEventProperties.class)
public class KafkaConfig {

    public static final String DLT_SUFFIX = "-dlt";

    static {
        TrustedEvents.trust();
    }

    // transaction-service owns the contract, so it creates every topic, the provider's too
    @Bean
    public KafkaAdmin.NewTopics eventTopics(KafkaEventProperties properties) {
        return new KafkaAdmin.NewTopics(
                topic(EventTopics.DEPOSIT_REQUESTED, properties),
                topic(EventTopics.DEPOSIT_COMPLETED, properties),
                topic(EventTopics.DEPOSIT_FAILED, properties),
                topic(EventTopics.WITHDRAWAL_REQUESTED, properties),
                topic(EventTopics.WITHDRAWAL_COMPLETED, properties),
                topic(EventTopics.WITHDRAWAL_FAILED, properties),
                topic(EventTopics.DEPOSIT_COMPLETED + DLT_SUFFIX, properties),
                topic(EventTopics.DEPOSIT_FAILED + DLT_SUFFIX, properties),
                topic(EventTopics.WITHDRAWAL_COMPLETED + DLT_SUFFIX, properties),
                topic(EventTopics.WITHDRAWAL_FAILED + DLT_SUFFIX, properties));
    }

    // a failure is retried with a pause; when the retries run out the message goes to <topic>-dlt.
    // A message that cannot be deserialized goes there at once: retrying would not help
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaTemplate,
                                                 KafkaEventProperties properties,
                                                 TransactionMetrics transactionMetrics) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (consumerRecord, exception) -> new TopicPartition(consumerRecord.topic() + DLT_SUFFIX, -1));
        FixedBackOff backOff = new FixedBackOff(properties.retryInterval().toMillis(), properties.retryAttempts());
        // counted after the publish: a failed publish throws, the message is retried and not counted twice
        return new DefaultErrorHandler((consumerRecord, exception) -> {
            deadLetters.accept(consumerRecord, exception);
            transactionMetrics.deadLettered(consumerRecord.topic());
        }, backOff);
    }

    private NewTopic topic(String name, KafkaEventProperties properties) {
        return TopicBuilder.name(name).partitions(properties.partitions()).replicas(1).build();
    }
}
