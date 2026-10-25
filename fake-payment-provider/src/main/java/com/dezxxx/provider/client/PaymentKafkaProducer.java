package com.dezxxx.provider.client;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// the answer joins the listener's Kafka transaction: it is visible to read_committed consumers
// only together with the offset of the request it answers
@Component
@RequiredArgsConstructor
public class PaymentKafkaProducer {

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public void send(String topic, UUID transactionId, SpecificRecord answer) {
        kafkaTemplate.send(topic, transactionId.toString(), answer);
    }
}
