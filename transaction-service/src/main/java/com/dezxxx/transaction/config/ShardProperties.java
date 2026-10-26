package com.dezxxx.transaction.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// the shards Flyway migrates one by one; ShardingSphere reads the same addresses from sharding.yaml
@ConfigurationProperties("transaction")
public record ShardProperties(List<Shard> shards) {

    public record Shard(String url, String username, String password) {
    }
}
