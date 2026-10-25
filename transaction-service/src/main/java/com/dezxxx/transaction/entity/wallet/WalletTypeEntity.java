package com.dezxxx.transaction.entity.wallet;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "wallet_types")
public class WalletTypeEntity {

    @Id
    private UUID uid;

    private Instant createdAt;

    private Instant modifiedAt;

    private String name;

    private String currencyCode;

    private String status;

    private Instant archivedAt;

    private String userType;

    private String creator;

    private String modifier;
}
