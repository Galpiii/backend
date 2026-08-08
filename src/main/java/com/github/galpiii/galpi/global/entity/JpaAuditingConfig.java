package com.github.galpiii.galpi.global.entity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.OffsetDateTime;
import java.util.Optional;

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = JpaAuditingConfig.DATE_TIME_PROVIDER)
public class JpaAuditingConfig {

    public static final String DATE_TIME_PROVIDER = "offsetDateTimeProvider";

    @Bean(DATE_TIME_PROVIDER)
    public static DateTimeProvider offsetDateTimeProvider() {
        return () -> Optional.of(OffsetDateTime.now());
    }
}
