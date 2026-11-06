package com.dezxxx.transaction.repository;

import com.dezxxx.transaction.entity.transaction.TransactionEntity;
import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

// every query carries user_uid: ShardingSphere goes to one shard. The PENDING metrics are the exception
public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID>,
        JpaSpecificationExecutor<TransactionEntity> {

    // empty when the requestId is already taken; a concurrent claim waits here until the first one ends.
    // The type is a literal: ShardingSphere rewrites CAST(:type AS payment_type) into broken SQL,
    // so each type has its own statement
    @Query(value = "INSERT INTO transactions (uid, user_uid, wallet_uid, amount, fee, type, status, request_id)"
            + " VALUES (:uid, :userUid, :walletUid, :amount, :fee, 'DEPOSIT', 'PENDING', :requestId)"
            + " ON CONFLICT (user_uid, request_id) DO NOTHING RETURNING uid", nativeQuery = true)
    Optional<UUID> claimDepositRequestId(UUID uid, UUID userUid, UUID walletUid, BigDecimal amount, BigDecimal fee,
                                         UUID requestId);

    @Query(value = "INSERT INTO transactions (uid, user_uid, wallet_uid, amount, fee, type, status, request_id)"
            + " VALUES (:uid, :userUid, :walletUid, :amount, :fee, 'WITHDRAWAL', 'PENDING', :requestId)"
            + " ON CONFLICT (user_uid, request_id) DO NOTHING RETURNING uid", nativeQuery = true)
    Optional<UUID> claimWithdrawalRequestId(UUID uid, UUID userUid, UUID walletUid, BigDecimal amount,
                                            BigDecimal fee, UUID requestId);

    @Query(value = "INSERT INTO transactions"
            + " (uid, user_uid, wallet_uid, target_wallet_uid, amount, fee, type, status, request_id)"
            + " VALUES (:uid, :userUid, :walletUid, :targetWalletUid, :amount, :fee, 'TRANSFER', 'PENDING',"
            + " :requestId)"
            + " ON CONFLICT (user_uid, request_id) DO NOTHING RETURNING uid", nativeQuery = true)
    Optional<UUID> claimTransferRequestId(UUID uid, UUID userUid, UUID walletUid, UUID targetWalletUid,
                                          BigDecimal amount, BigDecimal fee, UUID requestId);

    Optional<TransactionEntity> findByUserUidAndRequestId(UUID userUid, UUID requestId);

    @Modifying
    @Query("update TransactionEntity t"
            + " set t.status = com.dezxxx.transaction.entity.transaction.TransactionStatus.COMPLETED,"
            + " t.modifiedAt = :modifiedAt where t.uid = :uid and t.userUid = :userUid")
    int completeTransfer(UUID uid, UUID userUid, Instant modifiedAt);

    Optional<TransactionEntity> findByUidAndUserUid(UUID uid, UUID userUid);

    // only a PENDING transaction of the answer's type: 0 rows means a repeated answer, an unknown transaction
    // or an answer of another type. The type is compared as text, as in TransactionSpecifications.ofType:
    // ShardingSphere binds the parameter as varchar, and PostgreSQL has no payment_type = varchar
    @Modifying
    @Query("update TransactionEntity t set t.status = :status, t.failureReason = :failureReason,"
            + " t.modifiedAt = :modifiedAt where t.uid = :uid and t.userUid = :userUid"
            + " and cast(t.type as String) = :type"
            + " and t.status = com.dezxxx.transaction.entity.transaction.TransactionStatus.PENDING")
    int finishPending(UUID uid, UUID userUid, String type, TransactionStatus status,
                      String failureReason, Instant modifiedAt);

    // for TransactionMetrics, both shards: native with an alias, as in OutboxEventRepository
    @Query(value = "SELECT count(*) AS pending FROM transactions WHERE status = 'PENDING'", nativeQuery = true)
    long countPending();

    @Query(value = "SELECT max(EXTRACT(EPOCH FROM now() - created_at)) AS oldest FROM transactions"
            + " WHERE status = 'PENDING'", nativeQuery = true)
    Optional<Double> findOldestPendingAgeSeconds();
}
