package com.dezxxx.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("transaction.offer")
public record OfferTokenProperties (String secret,
                                    Duration ttl){
}
