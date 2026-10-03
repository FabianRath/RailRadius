package com.railreach.config;

import com.railreach.service.HubRoutingService;
import com.railreach.service.ReachabilityService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import jakarta.annotation.PostConstruct;

@Configuration
public class ServiceConfig {

    private final ReachabilityService reachabilityService;
    private final HubRoutingService hubRoutingService;

    // Use @Lazy to break circular dependency
    public ServiceConfig(ReachabilityService reachabilityService,
                        @Lazy HubRoutingService hubRoutingService) {
        this.reachabilityService = reachabilityService;
        this.hubRoutingService = hubRoutingService;
    }

    @PostConstruct
    public void init() {
        // Wire hub routing service into reachability service
        reachabilityService.setHubRoutingService(hubRoutingService);
    }
}
