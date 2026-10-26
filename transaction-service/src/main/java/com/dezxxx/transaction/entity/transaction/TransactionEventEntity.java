package com.dezxxx.transaction.entity.transaction;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// an event waiting for TransactionKafkaProducer: sent_at is NULL until the broker acknowledges it
@Getter
@Setter
@Entity
@Table(name = "transaction_events")
public class TransactionEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID uid;

    @CreationTimestamp
    private Instant createdAt;

    private UUID transactionUid;

    // the sharding key
    private UUID userUid;

    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    private String traceparent;

    private Instant sentAt;
}
