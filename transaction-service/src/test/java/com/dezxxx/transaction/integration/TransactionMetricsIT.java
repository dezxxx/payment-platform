package com.dezxxx.transaction.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.transaction.integration.support.IntegrationTest;
import com.dezxxx.transaction.metrics.TransactionMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("TransactionMetrics against both shards")
class TransactionMetricsIT extends IntegrationTest {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    // a user lands on a shard by hash: a few tries find both
    private static final int MAX_USERS = 50;

    @Autowired
    private TransactionMetrics transactionMetrics;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("given a PENDING deposit on each shard, one an hour old, when the metrics refresh,"
            + " then the gauge is the sum of both shards and the oldest is an hour old")
    void countsPendingOnBothShards() {
        // given
        UUID onShard0 = pendingOn(shard(SHARD_0));
        pendingOn(shard(SHARD_1));
        shard(SHARD_0).update("UPDATE transactions SET created_at = created_at - interval '1 hour' WHERE uid = ?",
                onShard0);

        // when
        transactionMetrics.refresh();

        // then
        assertThat(gauge("transaction.pending")).isEqualTo(pendingIn(shard(SHARD_0)) + pendingIn(shard(SHARD_1)));
        assertThat(gauge("transaction.pending.oldest")).isBetween(3500.0, 3700.0);
    }

    @Test
    @DisplayName("given the two pools ShardingSphere opens, when the meters are read, then each shard has its connection gauges")
    void bindsShardPools() {
        // then: ShardingSphere's default pool size, not Hikari's 10
        assertThat(meterRegistry.get("jdbc.connections.max").tag("name", "ds_0").gauge().value()).isEqualTo(50);
        assertThat(meterRegistry.get("jdbc.connections.max").tag("name", "ds_1").gauge().value()).isEqualTo(50);
        assertThat(meterRegistry.get("jdbc.connections.active").tag("name", "ds_0").gauge().value()).isNotNegative();
    }

    // confirms deposits of new users until one lands on this shard
    private UUID pendingOn(JdbcTemplate shard) {
        for (int i = 0; i < MAX_USERS; i++) {
            UUID user = UUID.randomUUID();
            UUID transactionId = confirmed(user, UUID.randomUUID(), createWallet(user), AMOUNT).getTransactionId();
            Integer rows = shard.queryForObject("SELECT count(*) FROM transactions WHERE uid = ?", Integer.class,
                    transactionId);
            if (rows == 1) {
                return transactionId;
            }
        }
        throw new IllegalStateException("No user landed on the shard in " + MAX_USERS + " tries");
    }

    private static int pendingIn(JdbcTemplate shard) {
        Integer rows = shard.queryForObject("SELECT count(*) FROM transactions WHERE status = 'PENDING'", Integer.class);
        return rows == null ? 0 : rows;
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }
}
