package com.dezxxx.transaction.repository;

import com.dezxxx.transaction.entity.transaction.TransactionEntity;
import com.dezxxx.transaction.entity.transaction.TransactionPaymentType;
import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

// the conditions of the search, one per filter; a filter not given adds no condition.
// ofUser is always there: ShardingSphere goes to one shard and only the user's rows are seen
public final class TransactionSpecifications {

    private TransactionSpecifications() {
    }

    public static Specification<TransactionEntity> ofUser(UUID userUid) {
        return (root, query, cb) -> cb.equal(root.get("userUid"), userUid);
    }

    public static Specification<TransactionEntity> ofWallet(UUID walletUid) {
        return walletUid == null ? unrestricted() : (root, query, cb) -> cb.equal(root.get("walletUid"), walletUid);
    }

    public static Specification<TransactionEntity> ofType(TransactionPaymentType type) {
        // the column is the PostgreSQL enum payment_type, and ShardingSphere binds the parameter as varchar:
        // compared as text, so both sides are strings
        return type == null ? unrestricted()
                : (root, query, cb) -> cb.equal(root.get("type").cast(String.class), type.name());
    }

    public static Specification<TransactionEntity> ofStatus(TransactionStatus status) {
        return status == null ? unrestricted() : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<TransactionEntity> createdFrom(Instant from) {
        return from == null ? unrestricted()
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    public static Specification<TransactionEntity> createdBefore(Instant to) {
        return to == null ? unrestricted() : (root, query, cb) -> cb.lessThan(root.get("createdAt"), to);
    }

    private static Specification<TransactionEntity> unrestricted() {
        return Specification.unrestricted();
    }
}
