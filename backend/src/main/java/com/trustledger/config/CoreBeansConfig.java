package com.trustledger.config;

import com.trustledger.core.fraud.FraudEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Exposes the dependency-free core domain services as Spring beans (kept Spring-free in {@code core}). */
@Configuration
@EnableScheduling
public class CoreBeansConfig {

    @Bean
    public FraudEngine fraudEngine() {
        return new FraudEngine();
    }
}
