package com.dezxxx.transaction.unit.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.dezxxx.transaction.entity.transaction.TransactionPaymentType;
import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import com.dezxxx.transaction.token.OfferTerms;
import com.dezxxx.transaction.token.OfferTokenSigner;
import com.dezxxx.transaction.validation.OfferTokenValidator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("OfferTokenValidator")
class OfferTokenValidatorTest {

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    private static final String OFFER_TOKEN = "payload.signature";

    private static final OfferTerms TERMS = new OfferTerms(UUID.randomUUID(), USER_UID,
            TransactionPaymentType.WITHDRAWAL, UUID.randomUUID(), null, new BigDecimal("100.00"),
            new BigDecimal("1.00"), Instant.parse("2026-10-29T12:05:00Z"), null);

    @Mock
    private OfferTokenSigner offerTokenSigner;

    private OfferTokenValidator validator;

    @BeforeEach
    void setUp() {
        validator = new OfferTokenValidator(offerTokenSigner);
        when(offerTokenSigner.readVerified(OFFER_TOKEN)).thenReturn(TERMS);
    }

    @Test
    @DisplayName("given the user's own token for this endpoint, when validated, then its terms")
    void returnsTermsOfOwnToken() {
        // when
        OfferTerms terms = validator.validate(OFFER_TOKEN, USER_UID, TransactionPaymentType.WITHDRAWAL);

        // then
        assertThat(terms).isSameAs(TERMS);
    }

    @Test
    @DisplayName("given a token issued to another user, when validated, then OFFER_TOKEN_INVALID")
    void refusesOtherUser() {
        assertThatThrownBy(() -> validator.validate(OFFER_TOKEN, UUID.randomUUID(), TransactionPaymentType.WITHDRAWAL))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OFFER_TOKEN_INVALID);
    }

    @Test
    @DisplayName("given a withdrawal token sent to the transfer confirm, when validated, then OFFER_TOKEN_INVALID")
    void refusesOtherType() {
        assertThatThrownBy(() -> validator.validate(OFFER_TOKEN, USER_UID, TransactionPaymentType.TRANSFER))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OFFER_TOKEN_INVALID);
    }
}
