package com.dezxxx.transaction.validation;

import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

// the dates of a search: a check across two query parameters, which a generated interface cannot carry
@Component
public class DateRangeValidator {

    // either date may be missing: the range is then open on that side
    public void requireOrdered(OffsetDateTime dateFrom, OffsetDateTime dateTo) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new TransactionServiceException(ErrorCode.VALIDATION_ERROR, "dateFrom must not be after dateTo");
        }
    }
}
