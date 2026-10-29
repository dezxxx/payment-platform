package com.dezxxx.individuals.gateway.transaction;

import com.dezxxx.transaction.client.model.TransactionStatusDto;
import com.dezxxx.transaction.client.model.TransactionTypeDto;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TransactionSearch(UUID walletUid,
                                TransactionTypeDto type,
                                TransactionStatusDto status,
                                OffsetDateTime dateFrom,
                                OffsetDateTime dateTo,
                                Integer page,
                                Integer size) {
}
