package com.dezxxx.transaction.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("transaction")
public record ShardProperties(List<Shard> shards) {

    public record Shard(String url, String username, String password) {
    }
}
