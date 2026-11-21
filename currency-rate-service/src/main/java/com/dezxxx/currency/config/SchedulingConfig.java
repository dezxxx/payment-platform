package com.dezxxx.currency.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "${currency.rates.lock-at-most-for}")
@EnableConfigurationProperties(RatesProperties.class)
public class SchedulingConfig {

    private static final String LOCK_TABLE = "shedlock";

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        // database time: every instance compares lock_until with one clock, not with its own
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .withTableName(LOCK_TABLE)
                .build());
    }
}
