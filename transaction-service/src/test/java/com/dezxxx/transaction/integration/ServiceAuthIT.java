package com.dezxxx.transaction.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.transaction.api.model.CreateWalletRequestDto;
import com.dezxxx.transaction.api.model.DepositConfirmRequestDto;
import com.dezxxx.transaction.integration.support.IntegrationTest;
import com.dezxxx.transaction.integration.support.TestTokens;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

@DisplayName("Only a platform service gets in: the service token with the realm role SERVICE")
class ServiceAuthIT extends IntegrationTest {

    private static final String WALLETS = "/api/v1/wallets";

    private static final String DEPOSIT_CONFIRM = "/api/v1/transactions/deposit/confirm";

    @Test
    @DisplayName("given no token, when creating a wallet straight on the published port, then 401"
            + " AUTHENTICATION_REQUIRED and no wallet")
    void refusesWithoutToken() {
        // given
        UUID user = UUID.randomUUID();

        // when / then
        createWallet(anonymousClient.post(), user)
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(wallets(user)).isZero();
    }

    @Test
    @DisplayName("given a user's token, not a service's, when creating a wallet, then 403 ACCESS_DENIED and no wallet")
    void refusesUserToken() {
        // given
        UUID user = UUID.randomUUID();

        // when / then
        createWallet(withToken(TestTokens.user()), user)
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("ACCESS_DENIED");
        assertThat(wallets(user)).isZero();
    }

    @Test
    @DisplayName("given a token with the role SERVICE signed by another key, when creating a wallet, then 401")
    void refusesForeignSignature() {
        // given
        UUID user = UUID.randomUUID();

        // when / then
        createWallet(withToken(TestTokens.signedByAnotherKey()), user)
                .expectStatus().isUnauthorized();
        assertThat(wallets(user)).isZero();
    }

    @Test
    @DisplayName("given a funded wallet and a valid offer, when a user's token confirms the deposit, then 403,"
            + " no transaction and the balance unchanged")
    void refusedConfirmMovesNoMoney() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = createWallet(user);
        fund(wallet, user, new BigDecimal("50.00"));
        String offerToken = depositOffer(user, wallet, new BigDecimal("100.00"));

        // when
        withToken(TestTokens.user()).uri(DEPOSIT_CONFIRM)
                .header(USER_UID_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new DepositConfirmRequestDto().offerToken(offerToken))
                .exchange()
                .expectStatus().isForbidden();

        // then
        assertThat(transactions(user)).isZero();
        assertThat(balance(wallet, user)).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("given the service token, when creating a wallet, then 201")
    void letsTheServiceIn() {
        // given
        UUID user = UUID.randomUUID();

        // when / then
        createWallet(client.post(), user).expectStatus().isCreated();
        assertThat(wallets(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("given no token, when Prometheus or Docker reads the actuator, then 200: infrastructure stays open")
    void leavesTheActuatorOpen() {
        // when / then
        anonymousClient.get().uri("/actuator/health").exchange().expectStatus().isOk();
        anonymousClient.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
    }

    private RestTestClient.RequestBodyUriSpec withToken(String token) {
        RestTestClient.RequestBodyUriSpec request = anonymousClient.post();
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }

    private static RestTestClient.ResponseSpec createWallet(RestTestClient.RequestBodyUriSpec request, UUID user) {
        return request.uri(WALLETS)
                .header(USER_UID_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateWalletRequestDto().name("Main USD").walletTypeUid(USD_TYPE))
                .exchange();
    }

    private int wallets(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM wallets WHERE user_uid = ?", Integer.class, user);
    }
}
