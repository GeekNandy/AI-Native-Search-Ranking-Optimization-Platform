package com.alpas.ainativesearchrankingoptimizationplatform.catalog;

import com.alpas.ainativesearchrankingoptimizationplatform.catalog.application.AdRepository;
import com.alpas.ainativesearchrankingoptimizationplatform.catalog.application.AdService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CatalogConfiguration {
    @Bean
    AdService adService(AdRepository repository) {
        return new AdService(repository, Clock.systemUTC());
    }
}
