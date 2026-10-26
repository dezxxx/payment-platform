package com.dezxxx.transaction.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.transaction.api.model.CreateWalletRequestDto;
import com.dezxxx.transaction.api.model.WalletResponseDto;
import com.dezxxx.transaction.integration.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

@DisplayName("WalletRestControllerV1 against two real shards")
class WalletRestControllerV1IT extends IntegrationTest {

    @Test
    @DisplayName("given an active wallet type, when creating, then 201, an ACTIVE wallet with balance 0 in the user's shard")
    void createsWallet() {
        // given
        UUID user = UUID.randomUUID();

        // when
        WalletResponseDto wallet = createWallet(user, "Main USD", USD_TYPE);

        // then
        assertThat(wallet.getUid()).isNotNull();
        assertThat(wallet.getName()).isEqualTo("Main USD");
        assertThat(wallet.getWalletTypeUid()).isEqualTo(USD_TYPE);
        assertThat(wallet.getCurrencyCode()).isEqualTo("USD");
        assertThat(wallet.getStatus()).isEqualTo("ACTIVE");
        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
        assertThat(rows(shard(SHARD_0), wallet.getUid()) + rows(shard(SHARD_1), wallet.getUid())).isEqualTo(1);
    }

    @Test
    @DisplayName("given an unknown wallet type, when creating, then 404 WALLET_TYPE_NOT_FOUND")
    void refusesUnknownType() {
        // when / then
        post(UUID.randomUUID(), new CreateWalletRequestDto().name("Main").walletTypeUid(UUID.randomUUID()))
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.error").isEqualTo("WALLET_TYPE_NOT_FOUND");
    }

    @Test
    @DisplayName("given an archived wallet type, when creating, then 400 WALLET_TYPE_NOT_ACTIVE")
    void refusesArchivedType() {
        // given
        UUID archived = UUID.randomUUID();
        jdbc.update("INSERT INTO wallet_types (uid, name, currency_code, status, archived_at)"
                + " VALUES (?, 'Old wallet', 'USD', 'ACTIVE', now())", archived);

        // when / then
        post(UUID.randomUUID(), new CreateWalletRequestDto().name("Main").walletTypeUid(archived))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("WALLET_TYPE_NOT_ACTIVE");
    }

    @Test
    @DisplayName("given a name longer than 32, when creating, then 400 VALIDATION_ERROR naming the field")
    void refusesLongName() {
        // when / then
        post(UUID.randomUUID(), new CreateWalletRequestDto().name("x".repeat(33)).walletTypeUid(USD_TYPE))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[0]").value(detail -> assertThat((String) detail).startsWith("name:"));
    }

    @Test
    @DisplayName("given no X-User-Uid header, when creating, then 400 VALIDATION_ERROR naming the header")
    void refusesMissingUser() {
        // when / then
        client.post().uri("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateWalletRequestDto().name("Main").walletTypeUid(USD_TYPE))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[0]").isEqualTo("X-User-Uid: is required");
    }

    @Test
    @DisplayName("given the user's wallet, when getting it by id, then 200 with balance, currency and status")
    void getsWallet() {
        // given
        UUID user = UUID.randomUUID();
        WalletResponseDto created = createWallet(user, "Main USD", USD_TYPE);

        // when
        WalletResponseDto wallet = client.get().uri("/api/v1/wallets/{walletUid}", created.getUid())
                .header(USER_UID_HEADER, user.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody(WalletResponseDto.class)
                .returnResult()
                .getResponseBody();

        // then
        assertThat(wallet).isEqualTo(created);
    }

    @Test
    @DisplayName("given another user's wallet, when getting it by id, then 404 WALLET_NOT_FOUND, not 403")
    void hidesAnotherUsersWallet() {
        // given
        WalletResponseDto foreign = createWallet(UUID.randomUUID(), "Main USD", USD_TYPE);

        // when / then
        client.get().uri("/api/v1/wallets/{walletUid}", foreign.getUid())
                .header(USER_UID_HEADER, UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("WALLET_NOT_FOUND");
    }

    @Test
    @DisplayName("given an archived wallet, when getting it by id, then 404 WALLET_NOT_FOUND")
    void hidesArchivedWallet() {
        // given
        UUID user = UUID.randomUUID();
        WalletResponseDto wallet = createWallet(user, "Main USD", USD_TYPE);
        jdbc.update("UPDATE wallets SET archived_at = now() WHERE uid = ? AND user_uid = ?", wallet.getUid(), user);

        // when / then
        client.get().uri("/api/v1/wallets/{walletUid}", wallet.getUid())
                .header(USER_UID_HEADER, user.toString())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("given a walletUid that is not a UUID, when getting it, then 400 VALIDATION_ERROR")
    void refusesBadWalletUid() {
        // when / then
        client.get().uri("/api/v1/wallets/not-a-uuid")
                .header(USER_UID_HEADER, UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("given a user with two wallets and an archived one, when listing, then 200 with the two active")
    void listsActiveWallets() {
        // given
        UUID user = UUID.randomUUID();
        WalletResponseDto usd = createWallet(user, "Main USD", USD_TYPE);
        WalletResponseDto eur = createWallet(user, "Main EUR", UUID.fromString("00000000-0000-0000-0000-000000000978"));
        WalletResponseDto archived = createWallet(user, "Old", USD_TYPE);
        jdbc.update("UPDATE wallets SET archived_at = now() WHERE uid = ? AND user_uid = ?", archived.getUid(), user);

        // when
        List<WalletResponseDto> wallets = listWallets(user);

        // then
        assertThat(wallets).extracting(WalletResponseDto::getUid).containsExactlyInAnyOrder(usd.getUid(), eur.getUid());
    }

    @Test
    @DisplayName("given a user without wallets, when listing, then 200 and an empty list")
    void listsNothing() {
        // when
        List<WalletResponseDto> wallets = listWallets(UUID.randomUUID());

        // then
        assertThat(wallets).isEmpty();
    }

    private WalletResponseDto createWallet(UUID user, String name, UUID walletType) {
        return post(user, new CreateWalletRequestDto().name(name).walletTypeUid(walletType))
                .expectStatus().isCreated()
                .expectBody(WalletResponseDto.class)
                .returnResult()
                .getResponseBody();
    }

    private RestTestClient.ResponseSpec post(UUID user, CreateWalletRequestDto request) {
        return client.post().uri("/api/v1/wallets")
                .header(USER_UID_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange();
    }

    private List<WalletResponseDto> listWallets(UUID user) {
        return client.get().uri("/api/v1/wallets/user/{userUid}", user)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<WalletResponseDto>>() {
                })
                .returnResult()
                .getResponseBody();
    }

    private static int rows(JdbcTemplate shard, UUID wallet) {
        Integer count = shard.queryForObject("SELECT count(*) FROM wallets WHERE uid = ?", Integer.class, wallet);
        return count == null ? 0 : count;
    }
}
