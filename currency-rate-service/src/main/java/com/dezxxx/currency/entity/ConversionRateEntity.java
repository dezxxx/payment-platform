package com.dezxxx.currency.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Setter
@Entity
@Table(name = "conversion_rates")
public class ConversionRateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sourceCode;

    private String destinationCode;

    private BigDecimal rate;

    private Instant rateBeginTime;

    private Instant rateEndTime;

    private String providerCode;

    @CreationTimestamp
    private Instant createdAt;
}
