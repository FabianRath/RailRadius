package com.railreach.config;

import com.railreach.service.SearchEngine;
import com.railreach.service.TransitGraph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The search engine every search runs on, by {@code railreach.search.engine} (until S21 removes the switch). */
@Configuration
@Slf4j
public class SearchEngineConfig {

    @Bean
    public SearchEngine searchEngine(@Value("${railreach.search.engine:legacy}") String engine, TransitGraph graph) {
        log.info("Search engine: {}", engine);
        return SearchEngine.named(engine, graph);
    }
}
