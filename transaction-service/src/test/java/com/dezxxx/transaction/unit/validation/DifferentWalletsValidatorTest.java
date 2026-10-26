package com.dezxxx.transaction.unit.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.transaction.api.model.TransferConfirmRequestDto;
import com.dezxxx.transaction.api.model.TransferInitRequestDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DifferentWallets")
class DifferentWalletsValidatorTest {

    private static final String SAME_WALLET_MESSAGE = "walletUid and targetWalletUid must be different wallets";

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private static ValidatorFactory factory;

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("accepts a transfer between two different wallets")
    void acceptsDifferentWallets() {
        // given
        TransferInitRequestDto request = initRequest(UUID.randomUUID(), UUID.randomUUID());

        // when
        Set<ConstraintViolation<TransferInitRequestDto>> violations = validator.validate(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("rejects an init request that sends money to the same wallet")
    void rejectsSameWalletOnInit() {
        // given
        UUID wallet = UUID.randomUUID();
        TransferInitRequestDto request = initRequest(wallet, wallet);

        // when
        Set<ConstraintViolation<TransferInitRequestDto>> violations = validator.validate(request);

        // then
        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactly(SAME_WALLET_MESSAGE);
    }

    @Test
    @DisplayName("puts the error on targetWalletUid, so the response details name the field")
    void reportsOnTargetWallet() {
        // given
        UUID wallet = UUID.randomUUID();
        TransferInitRequestDto request = initRequest(wallet, wallet);

        // when
        Set<ConstraintViolation<TransferInitRequestDto>> violations = validator.validate(request);

        // then
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("targetWalletUid");
    }

    @Test
    @DisplayName("rejects a confirm request that sends money to the same wallet")
    void rejectsSameWalletOnConfirm() {
        // given
        UUID wallet = UUID.randomUUID();
        TransferConfirmRequestDto request = new TransferConfirmRequestDto(UUID.randomUUID(), wallet, wallet, AMOUNT);

        // when
        Set<ConstraintViolation<TransferConfirmRequestDto>> violations = validator.validate(request);

        // then
        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .containsExactly(SAME_WALLET_MESSAGE);
    }

    @Test
    @DisplayName("stays silent when a wallet is missing, leaving that to @NotNull")
    void ignoresMissingWallet() {
        // given
        TransferInitRequestDto request = initRequest(UUID.randomUUID(), null);

        // when
        Set<ConstraintViolation<TransferInitRequestDto>> violations = validator.validate(request);

        // then
        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .doesNotContain(SAME_WALLET_MESSAGE);
    }

    private static TransferInitRequestDto initRequest(UUID walletUid, UUID targetWalletUid) {
        return new TransferInitRequestDto(walletUid, targetWalletUid, AMOUNT);
    }
}
