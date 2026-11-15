package com.dezxxx.currency.repository;

import com.dezxxx.currency.entity.ConversionRateEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConversionRateRepository extends JpaRepository<ConversionRateEntity, Long> {

    // a rate lives 3 h and a new one is written every hour: up to three rows are valid at once, the newest wins
    @Query("select r from ConversionRateEntity r"
            + " where r.sourceCode = :sourceCode and r.destinationCode = :destinationCode"
            + " and r.rateBeginTime <= :moment and r.rateEndTime > :moment"
            + " order by r.rateBeginTime desc limit 1")
    Optional<ConversionRateEntity> findValid(String sourceCode, String destinationCode, Instant moment);

    @Query("select max(r.rateBeginTime) from ConversionRateEntity r")
    Optional<Instant> findNewestBeginTime();

    @Query("select count(distinct concat(r.sourceCode, r.destinationCode)) from ConversionRateEntity r"
            + " where r.rateBeginTime <= :moment and r.rateEndTime > :moment")
    long countValidPairs(Instant moment);
}
