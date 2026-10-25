package com.dezxxx.transaction.repository;

import com.dezxxx.transaction.entity.transaction.TransactionEntity;
import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

// every query carries user_uid: ShardingSphere goes to one shard. The PENDING metrics are the exception
public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    // empty when the requestId is already taken; a concurrent claim waits here until the first one ends.
    // The type is a literal: ShardingSphere rewrites CAST(:type AS payment_type) into broken SQL
    @Query(value = "INSERT INTO transactions (uid, user_uid, wallet_uid, amount, type, status, request_id)"
            + " VALUES (:uid, :userUid, :walletUid, :amount, 'DEPOSIT', 'PENDING', :requestId)"
            + " ON CONFLICT (user_uid, request_id) DO NOTHING RETURNING uid", nativeQuery = true)
    Optional<UUID> claimDepositRequestId(UUID uid, UUID userUid, UUID walletUid, BigDecimal amount, UUID requestId);

    Optional<TransactionEntity> findByUserUidAndRequestId(UUID userUid, UUID requestId);

    @Modifying
    @Query("update TransactionEntity t set t.fee = :fee where t.uid = :uid and t.userUid = :userUid")
    int updateFee(UUID uid, UUID userUid, BigDecimal fee);

    Optional<TransactionEntity> findByUidAndUserUid(UUID uid, UUID userUid);

    // only from PENDING: 0 rows means a repeated answer or an unknown transaction
    @Modifying
    @Query("update TransactionEntity t set t.status = :status, t.failureReason = :failureReason,"
            + " t.modifiedAt = :modifiedAt where t.uid = :uid and t.userUid = :userUid"
            + " and t.status = com.dezxxx.transaction.entity.transaction.TransactionStatus.PENDING")
    int finishPending(UUID uid, UUID userUid, TransactionStatus status, String failureReason, Instant modifiedAt);

    // for TransactionMetrics, both shards: native with an alias, as in TransactionEventRepository
    @Query(value = "SELECT count(*) AS pending FROM transactions WHERE status = 'PENDING'", nativeQuery = true)
    long countPending();

    @Query(value = "SELECT max(EXTRACT(EPOCH FROM now() - created_at)) AS oldest FROM transactions"
            + " WHERE status = 'PENDING'", nativeQuery = true)
    Optional<Double> findOldestPendingAgeSeconds();
}
