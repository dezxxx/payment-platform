package com.dezxxx.transaction.entity.transaction;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// the row is created by the requestId claim in TransactionRepository, not by save
@Getter
@Setter
@Entity
@Table(name = "transactions")
public class TransactionEntity {

    @Id
    private UUID uid;

    private Instant createdAt;

    private Instant modifiedAt;

    // the sharding key
    private UUID userUid;

    private UUID walletUid;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private TransactionPaymentType type;

    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    private BigDecimal fee;

    private UUID targetWalletUid;

    private String failureReason;

    private UUID requestId;
}
