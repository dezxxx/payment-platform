package com.dezxxx.transaction.metrics;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.driver.jdbc.core.connection.ShardingSphereConnection;
import org.apache.shardingsphere.infra.metadata.database.resource.unit.StorageUnit;
import org.springframework.boot.jdbc.DataSourceUnwrapper;
import org.springframework.boot.jdbc.metadata.DataSourcePoolMetadataProvider;
import org.springframework.boot.jdbc.metadata.HikariDataSourcePoolMetadata;
import org.springframework.boot.jdbc.metrics.DataSourcePoolMetrics;
import org.springframework.stereotype.Component;

// the pools ShardingSphere opens to each shard. Spring sees only the pool in front of ShardingSphere,
// so these are bound here with Spring Boot's own binder: jdbc.connections.active{name="ds_0"} and so on
@Component
@RequiredArgsConstructor
public class ShardPoolMetrics implements MeterBinder {

    // ShardingSphere wraps each Hikari pool; the unwrapper reaches it through JDBC's unwrap
    private static final DataSourcePoolMetadataProvider HIKARI = dataSource -> {
        HikariDataSource hikari = DataSourceUnwrapper.unwrap(dataSource, HikariConfigMXBean.class, HikariDataSource.class);
        return hikari == null ? null : new HikariDataSourcePoolMetadata(hikari);
    };

    private final DataSource dataSource;

    @Override
    public void bindTo(MeterRegistry registry) {
        shardPools().forEach((name, unit) ->
                new DataSourcePoolMetrics(unit.getDataSource(), HIKARI, name, Tags.empty()).bindTo(registry));
    }

    // the storage units of sharding.yaml, ds_0 and ds_1, read from the context behind a ShardingSphere connection
    private Map<String, StorageUnit> shardPools() {
        try (Connection connection = dataSource.getConnection()) {
            ShardingSphereConnection sharding = connection.unwrap(ShardingSphereConnection.class);
            return sharding.getContextManager().getStorageUnits(sharding.getCurrentDatabaseName());
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read the shard pools from ShardingSphere", ex);
        }
    }
}
