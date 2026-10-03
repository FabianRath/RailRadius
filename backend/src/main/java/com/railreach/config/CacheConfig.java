package com.railreach.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.TimeUnit;


@Configuration
@EnableAsync
public class CacheConfig {
    
    @Value("${railreach.algorithm.cache-size:10000}")
    private int cacheSize;
    
    @Value("${railreach.algorithm.cache-ttl-minutes:60}")
    private int cacheTtlMinutes;
    
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("reachability");
        cacheManager.setCaffeine(caffeineCacheBuilder());
        // Allow dynamic creation of cache entries (needed for @Cacheable with runtime keys)
        cacheManager.setAllowNullValues(false);
        return cacheManager;
    }
    
    private Caffeine<Object, Object> caffeineCacheBuilder() {
        return Caffeine.newBuilder()
                .maximumSize(cacheSize)
                .expireAfterWrite(cacheTtlMinutes, TimeUnit.MINUTES)
                .recordStats();  // Enables hit/miss stats exposed via /api/admin/cache-stats
    }
}
