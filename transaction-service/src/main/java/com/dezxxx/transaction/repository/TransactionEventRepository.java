package com.dezxxx.transaction.repository;

import com.dezxxx.transaction.entity.transaction.TransactionEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface TransactionEventRepository extends JpaRepository<TransactionEventEntity, UUID> {

    // no user_uid on purpose: ShardingSphere reads both shards and merges them by created_at
    @Query(value = "SELECT * FROM transaction_events WHERE sent_at IS NULL ORDER BY created_at LIMIT :limit",
            nativeQuery = true)
    List<TransactionEventEntity> findUnsent(int limit);

    // for TransactionMetrics, both shards. Native with an alias: ShardingSphere merges an aggregate by its alias,
    // and the JPQL ones come without. The age is counted by the database: now() and created_at share its time zone
    @Query(value = "SELECT count(*) AS unsent FROM transaction_events WHERE sent_at IS NULL", nativeQuery = true)
    long countUnsent();

    @Query(value = "SELECT max(EXTRACT(EPOCH FROM now() - created_at)) AS oldest FROM transaction_events"
            + " WHERE sent_at IS NULL", nativeQuery = true)
    Optional<Double> findOldestUnsentAgeSeconds();

    @Modifying
    @Transactional
    @Query("update TransactionEventEntity e set e.sentAt = :sentAt where e.uid = :uid and e.userUid = :userUid")
    int markSent(UUID uid, UUID userUid, Instant sentAt);
}
