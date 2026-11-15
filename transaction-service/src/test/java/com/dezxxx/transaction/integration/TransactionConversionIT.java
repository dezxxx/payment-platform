package com.dezxxx.transaction.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.transaction.api.model.CreateWalletRequestDto;
import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.api.model.TransactionStatusDto;
import com.dezxxx.transaction.api.model.TransactionStatusResponseDto;
import com.dezxxx.transaction.api.model.TransferInitRequestDto;
import com.dezxxx.transaction.api.model.WalletResponseDto;
import com.dezxxx.transaction.integration.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;

@DisplayName("TransactionRestControllerV1: transfer between currencies")
class TransactionConversionIT extends IntegrationTest {

    private static final UUID UAH_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000980");

    private static final UUID EUR_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000978");

    // Open Exchange Rates on 2026-11-14: UAH → EUR and UAH → USD
    private static final BigDecimal UAH_EUR = new BigDecimal("0.01994710");

    private static final BigDecimal UAH_USD = new BigDecimal("0.02198874");

    private static final BigDecimal AMOUNT = new BigDecimal("150.00");

    private static final BigDecimal FUNDS = new BigDecimal("200.00");

    @Test
    @DisplayName("given a UAH and an EUR wallet with the rates, when init, then 200 with fee 0.75, total 150.75,"
            + " the rate and targetAmount 2.99, nothing written")
    void initsConversion() {
        // given
        UUID user = UUID.randomUUID();
        UUID source = createWallet(user, UAH_TYPE);
        UUID target = createWallet(user, EUR_TYPE);
        fund(source, user, FUNDS);

        // when
        TransactionInitResponseDto offer = init(user, conversion(source, target, AMOUNT))
                .expectStatus().isOk()
                .expectBody(TransactionInitResponseDto.class)
                .returnResult()
                .getResponseBody();

        // then
        assertThat(offer.getFee()).isEqualByComparingTo("0.75");
        assertThat(offer.getTotal()).isEqualByComparingTo("150.75");
        assertThat(offer.getRate()).isEqualByComparingTo("0.01994710");
        assertThat(offer.getTargetAmount()).isEqualByComparingTo("2.99");
        assertThat(transactions(user)).isZero();
    }

    @Test
    @DisplayName("given the offer, when confirm, then amount + fee off the UAH wallet, targetAmount on the EUR wallet,"
            + " the rate and targetAmount in the row and in the status")
    void confirmsConversion() {
        // given
        UUID user = UUID.randomUUID();
        UUID source = createWallet(user, UAH_TYPE);
        UUID target = createWallet(user, EUR_TYPE);
        fund(source, user, FUNDS);
        String offerToken = offerToken(user, conversion(source, target, AMOUNT));

        // when
        TransactionConfirmResponseDto confirmed = ok(confirmTransfer(user, offerToken));

        // then
        assertThat(confirmed.getStatus()).isEqualTo(TransactionStatusDto.COMPLETED);
        assertThat(balance(source, user)).isEqualByComparingTo("49.25");
        assertThat(balance(target, user)).isEqualByComparingTo("2.99");
        Map<String, Object> row = jdbc.queryForMap("SELECT amount, fee, rate, target_amount FROM transactions"
                + " WHERE uid = ? AND user_uid = ?", confirmed.getTransactionId(), user);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("150.00");
        assertThat((BigDecimal) row.get("fee")).isEqualByComparingTo("0.75");
        assertThat((BigDecimal) row.get("rate")).isEqualByComparingTo("0.01994710");
        assertThat((BigDecimal) row.get("target_amount")).isEqualByComparingTo("2.99");
        TransactionStatusResponseDto status = client.get()
                .uri("/api/v1/transactions/{id}/status", confirmed.getTransactionId())
                .header(USER_UID_HEADER, user.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody(TransactionStatusResponseDto.class)
                .returnResult()
                .getResponseBody();
        assertThat(status.getRate()).isEqualByComparingTo("0.01994710");
        assertThat(status.getTargetAmount()).isEqualByComparingTo("2.99");
    }

    @Test
    @DisplayName("given 20 UAH, worth 0.44 USD, when init, then 400 AMOUNT_TOO_SMALL")
    void refusesBelowMinimum() {
        // given
        UUID user = UUID.randomUUID();
        UUID source = createWallet(user, UAH_TYPE);
        UUID target = createWallet(user, EUR_TYPE);
        fund(source, user, FUNDS);

        // when / then
        init(user, conversion(source, target, new BigDecimal("20.00")))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("AMOUNT_TOO_SMALL");
    }

    @Test
    @DisplayName("given a rate that turns the amount into 0.00, when init, then 400 AMOUNT_TOO_SMALL: nothing to credit")
    void refusesZeroTargetAmount() {
        // given
        UUID user = UUID.randomUUID();
        UUID source = createWallet(user, UAH_TYPE);
        UUID target = createWallet(user, EUR_TYPE);
        fund(source, user, FUNDS);
        TransferInitRequestDto request = conversion(source, target, AMOUNT).rate(new BigDecimal("0.00001"));

        // when / then
        init(user, request)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("AMOUNT_TOO_SMALL");
    }

    @Test
    @DisplayName("given codes that are not the wallets' currencies, when init, then 400 CURRENCY_MISMATCH")
    void refusesWrongCodes() {
        // given
        UUID user = UUID.randomUUID();
        UUID source = createWallet(user, UAH_TYPE);
        UUID target = createWallet(user, EUR_TYPE);
        fund(source, user, FUNDS);
        TransferInitRequestDto request = conversion(source, target, AMOUNT).sourceCode("USD");

        // when / then
        init(user, request)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    @DisplayName("given two currencies and the codes but no rate, when init, then 400 CURRENCY_MISMATCH")
    void refusesMissingRate() {
        // given
        UUID user = UUID.randomUUID();
        UUID source = createWallet(user, UAH_TYPE);
        UUID target = createWallet(user, EUR_TYPE);
        fund(source, user, FUNDS);
        TransferInitRequestDto request = conversion(source, target, AMOUNT).rate(null);

        // when / then
        init(user, request)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    @DisplayName("given two USD wallets and a rate, when init, then 400 CURRENCY_MISMATCH: one currency takes no rate")
    void refusesRateForOneCurrency() {
        // given
        UUID user = UUID.randomUUID();
        UUID source = createWallet(user);
        UUID target = createWallet(user);
        fund(source, user, FUNDS);
        TransferInitRequestDto request = new TransferInitRequestDto().walletUid(source).targetWalletUid(target)
                .amount(AMOUNT).rate(UAH_EUR).rateToUsd(UAH_USD);

        // when / then
        init(user, request)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("CURRENCY_MISMATCH");
    }

    private static TransferInitRequestDto conversion(UUID source, UUID target, BigDecimal amount) {
        return new TransferInitRequestDto().walletUid(source).targetWalletUid(target).amount(amount)
                .sourceCode("UAH").destinationCode("EUR").rate(UAH_EUR).rateToUsd(UAH_USD);
    }

    private RestTestClient.ResponseSpec init(UUID user, TransferInitRequestDto request) {
        return post(user, "/api/v1/transactions/transfer/init", request);
    }

    private String offerToken(UUID user, TransferInitRequestDto request) {
        return init(user, request)
                .expectStatus().isOk()
                .expectBody(TransactionInitResponseDto.class)
                .returnResult()
                .getResponseBody()
                .getOfferToken();
    }

    private UUID createWallet(UUID user, UUID walletType) {
        return post(user, "/api/v1/wallets", new CreateWalletRequestDto().name("Main").walletTypeUid(walletType))
                .expectStatus().isCreated()
                .expectBody(WalletResponseDto.class)
                .returnResult()
                .getResponseBody()
                .getUid();
    }
}
