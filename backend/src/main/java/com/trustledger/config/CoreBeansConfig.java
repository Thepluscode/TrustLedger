package com.trustledger.config;

import com.trustledger.core.fraud.FraudEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes the dependency-free core domain services as Spring beans (kept Spring-free in {@code core}). */
@Configuration
public class CoreBeansConfig {

    @Bean
    public FraudEngine fraudEngine() {
        return new FraudEngine();
    }
}
