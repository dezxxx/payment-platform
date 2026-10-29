package com.dezxxx.transaction.validation;

import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

@Component
public class DateRangeValidator {

    public void requireOrdered(OffsetDateTime dateFrom, OffsetDateTime dateTo) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new TransactionServiceException(ErrorCode.VALIDATION_ERROR, "dateFrom must not be after dateTo");
        }
    }
}
