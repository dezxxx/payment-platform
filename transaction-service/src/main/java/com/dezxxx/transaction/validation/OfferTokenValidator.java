package com.dezxxx.transaction.validation;

import com.dezxxx.transaction.entity.transaction.TransactionPaymentType;
import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import com.dezxxx.transaction.token.OfferTerms;
import com.dezxxx.transaction.token.OfferTokenSigner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OfferTokenValidator {

    private final OfferTokenSigner offerTokenSigner;

    public OfferTerms validate(String offerToken, UUID userUid, TransactionPaymentType type) {
        OfferTerms terms = offerTokenSigner.readVerified(offerToken);
        requireOwner(terms, userUid);
        requireType(terms, type);
        return terms;
    }

    private static void requireOwner(OfferTerms terms, UUID userUid) {
        if (!terms.userUid().equals(userUid)) {
            throw new TransactionServiceException(ErrorCode.OFFER_TOKEN_INVALID);
        }
    }

    private static void requireType(OfferTerms terms, TransactionPaymentType type) {
        if (terms.type() != type) {
            throw new TransactionServiceException(ErrorCode.OFFER_TOKEN_INVALID);
        }
    }
}
