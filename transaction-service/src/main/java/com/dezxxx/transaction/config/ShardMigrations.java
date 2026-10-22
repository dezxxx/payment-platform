package com.dezxxx.transaction.config;

import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AbstractDependsOnBeanFactoryPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Flyway talks to each shard directly: through ShardingSphere it would see one logical database
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(ShardProperties.class)
public class ShardMigrations implements InitializingBean {

    private final ShardProperties properties;

    // ShardingSphere reads the tables on its first connection, so the shards are migrated before the DataSource exists
    @Bean
    static BeanFactoryPostProcessor dataSourceDependsOnShardMigrations() {
        return new AbstractDependsOnBeanFactoryPostProcessor(DataSource.class, "shardMigrations") {
        };
    }

    @Override
    public void afterPropertiesSet() {
        properties.shards().forEach(this::migrate);
    }

    private void migrate(ShardProperties.Shard shard) {
        MigrateResult result = Flyway.configure()
                .dataSource(shard.url(), shard.username(), shard.password())
                .load()
                .migrate();
        log.info("Shard {} migrated: {} migrations applied, schema version {}",
                shard.url(), result.migrationsExecuted, result.targetSchemaVersion);
    }
}
