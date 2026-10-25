package com.dezxxx.transaction.repository;

import com.dezxxx.transaction.entity.wallet.WalletEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

// every query carries user_uid: ShardingSphere goes to one shard, and another user's wallet is not found
public interface WalletRepository extends JpaRepository<WalletEntity, UUID> {

    @Query("select w from WalletEntity w join fetch w.walletType"
            + " where w.uid = :walletUid and w.userUid = :userUid and w.archivedAt is null")
    Optional<WalletEntity> findActive(UUID walletUid, UUID userUid);

    @Query("select w from WalletEntity w join fetch w.walletType"
            + " where w.userUid = :userUid and w.archivedAt is null")
    List<WalletEntity> findAllActive(UUID userUid);

    // another operation on this wallet waits until this transaction ends, so the balance checked before a debit
    // cannot change. NO KEY UPDATE, not UPDATE: the claim's foreign key has already put FOR KEY SHARE on this row,
    // and FOR UPDATE would wait for that lock of a concurrent claim - two confirms would deadlock.
    // Only the wallet row is locked; its type is read later, without a lock
    @Query(value = "SELECT * FROM wallets WHERE uid = :walletUid AND user_uid = :userUid AND archived_at IS NULL"
            + " FOR NO KEY UPDATE", nativeQuery = true)
    Optional<WalletEntity> findActiveForUpdate(UUID walletUid, UUID userUid);

    // in the database, not read-modify-write: two credits at once both count
    @Modifying
    @Query("update WalletEntity w set w.balance = w.balance + :amount, w.modifiedAt = :modifiedAt"
            + " where w.uid = :walletUid and w.userUid = :userUid")
    int credit(UUID walletUid, UUID userUid, BigDecimal amount, Instant modifiedAt);

    @Modifying
    @Query("update WalletEntity w set w.balance = w.balance - :amount, w.modifiedAt = :modifiedAt"
            + " where w.uid = :walletUid and w.userUid = :userUid")
    int debit(UUID walletUid, UUID userUid, BigDecimal amount, Instant modifiedAt);
}
