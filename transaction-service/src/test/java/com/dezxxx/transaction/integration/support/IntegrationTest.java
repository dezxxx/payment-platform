package com.dezxxx.transaction.integration.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
public abstract class IntegrationTest {

    private static final String POSTGRES_IMAGE = "postgres:18.2";

    protected static final PostgreSQLContainer SHARD_0 = new PostgreSQLContainer(POSTGRES_IMAGE);

    protected static final PostgreSQLContainer SHARD_1 = new PostgreSQLContainer(POSTGRES_IMAGE);

    static {
        SHARD_0.start();
        SHARD_1.start();
        // the names sharding.yaml and application.yml read; in the container they come from the environment
        System.setProperty("TRANSACTION_SHARD_0_URL", SHARD_0.getJdbcUrl());
        System.setProperty("TRANSACTION_SHARD_1_URL", SHARD_1.getJdbcUrl());
        System.setProperty("TRANSACTION_DB_USER", SHARD_0.getUsername());
        System.setProperty("TRANSACTION_DB_PASSWORD", SHARD_0.getPassword());
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void shards(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:shardingsphere:classpath:sharding.yaml?placeholder-type=system_props");
    }

    // straight to one shard, past ShardingSphere: shows where a row really is
    protected static JdbcTemplate shard(PostgreSQLContainer container) {
        return new JdbcTemplate(new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword()));
    }
}
