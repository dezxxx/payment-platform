package com.dezxxx.transaction.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.dezxxx.transaction.integration.support.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("Shards, Flyway and the requestId claim")
class ShardingIT extends IntegrationTest {

    private static final String CLAIM = "INSERT INTO transactions (uid, user_uid, wallet_uid, amount, type, status, request_id)"
            + " VALUES (?, ?, ?, 100.00, 'DEPOSIT', 'PENDING', ?)"
            + " ON CONFLICT (user_uid, request_id) DO NOTHING RETURNING uid";

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("given the service started, when reading Flyway history of each shard, then V001 to V003 are applied on both")
    void migratesEveryShard() {
        // when
        List<String> shard0 = versions(shard(SHARD_0));
        List<String> shard1 = versions(shard(SHARD_1));

        // then
        assertThat(shard0).containsExactly("001", "002", "003");
        assertThat(shard1).containsExactly("001", "002", "003");
    }

    @Test
    @DisplayName("given a wallet type written through ShardingSphere, when reading each shard, then both hold it")
    void copiesWalletTypesToEveryShard() {
        // given
        UUID walletType = walletType();

        // when / then
        assertThat(rows(shard(SHARD_0), "wallet_types", walletType)).isEqualTo(1);
        assertThat(rows(shard(SHARD_1), "wallet_types", walletType)).isEqualTo(1);
    }

    @Test
    @DisplayName("given a wallet written through ShardingSphere, when reading each shard, then exactly one holds it")
    void putsAWalletIntoOneShard() {
        // given
        UUID wallet = wallet(UUID.randomUUID(), walletType());

        // when
        int inShard0 = rows(shard(SHARD_0), "wallets", wallet);
        int inShard1 = rows(shard(SHARD_1), "wallets", wallet);

        // then
        assertThat(inShard0 + inShard1).isEqualTo(1);
    }

    @Test
    @DisplayName("given a claim not committed yet, when a second claim of the same requestId comes, then it waits and gets no row")
    void secondClaimOfOneRequestIdWaitsAndGetsNothing() throws Exception {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = wallet(user, walletType());
        UUID requestId = UUID.randomUUID();

        try (Connection first = dataSource.getConnection()) {
            first.setAutoCommit(false);
            assertThat(claim(first, user, wallet, requestId)).isTrue();

            // when
            CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> claimInOwnTransaction(user, wallet, requestId));
            Thread.sleep(500);
            boolean waited = !second.isDone();
            first.commit();

            // then
            assertThat(waited).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM transactions WHERE user_uid = ? AND request_id = ?", Integer.class, user, requestId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("given a negative balance written through ShardingSphere, when it reaches the shard, then the CHECK refuses it")
    void refusesANegativeBalance() {
        // given
        UUID user = UUID.randomUUID();
        UUID wallet = wallet(user, walletType());

        // when
        Throwable refused = catchThrowable(() -> jdbc.update(
                "UPDATE wallets SET balance = balance - 1 WHERE uid = ? AND user_uid = ?", wallet, user));

        // then
        assertThat(refused).hasMessageContaining("ck_wallets_balance_not_negative");
    }

    private boolean claimInOwnTransaction(UUID user, UUID wallet, UUID requestId) {
        try (Connection second = dataSource.getConnection()) {
            second.setAutoCommit(false);
            boolean claimed = claim(second, user, wallet, requestId);
            second.commit();
            return claimed;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean claim(Connection connection, UUID user, UUID wallet, UUID requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CLAIM)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, user);
            statement.setObject(3, wallet);
            statement.setObject(4, requestId);
            try (ResultSet returned = statement.executeQuery()) {
                return returned.next();
            }
        }
    }

    private UUID walletType() {
        UUID uid = UUID.randomUUID();
        jdbc.update("INSERT INTO wallet_types (uid, name, currency_code, status) VALUES (?, 'USD wallet', 'USD', 'ACTIVE')", uid);
        return uid;
    }

    private UUID wallet(UUID user, UUID walletType) {
        UUID uid = UUID.randomUUID();
        jdbc.update("INSERT INTO wallets (uid, name, wallet_type_uid, user_uid, status, balance) VALUES (?, 'Main', ?, ?, 'ACTIVE', 0)",
                uid, walletType, user);
        return uid;
    }

    private static List<String> versions(JdbcTemplate shard) {
        return shard.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String.class);
    }

    private static int rows(JdbcTemplate shard, String table, UUID uid) {
        Integer count = shard.queryForObject("SELECT count(*) FROM " + table + " WHERE uid = ?", Integer.class, uid);
        return count == null ? 0 : count;
    }
}
