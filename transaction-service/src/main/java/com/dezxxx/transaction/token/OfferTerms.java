package com.dezxxx.transaction.token;

import com.dezxxx.transaction.entity.transaction.TransactionPaymentType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OfferTerms(UUID requestId,
                         UUID userUid,
                         TransactionPaymentType type,
                         UUID walletUid,
                         UUID targetWalletUid,
                         BigDecimal amount,
                         BigDecimal fee,
                         Instant expiresAt) {
}
