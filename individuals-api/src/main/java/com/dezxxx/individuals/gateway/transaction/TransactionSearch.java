package com.dezxxx.individuals.gateway.transaction;

import com.dezxxx.transaction.client.model.TransactionStatusDto;
import com.dezxxx.transaction.client.model.TransactionTypeDto;
import java.time.OffsetDateTime;
import java.util.UUID;

// the search filters without the user: TransactionClient adds the user_uid from the token
public record TransactionSearch(UUID walletUid,
                                TransactionTypeDto type,
                                TransactionStatusDto status,
                                OffsetDateTime dateFrom,
                                OffsetDateTime dateTo,
                                Integer page,
                                Integer size) {
}
