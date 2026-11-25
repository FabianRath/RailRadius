package com.railradius;

import com.railradius.controller.RailRadiusController;
import com.railradius.service.ReachabilityWarmer;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RailRadiusBackendApplicationTests {

	@Autowired
	ReachabilityWarmer warmer;

	@Autowired
	PathMappedEndpoints actuatorEndpoints;

	@Autowired
	RailRadiusController controller;

	@Test
	void contextLoads() {
	}

	@Test
	void warmingIsCalledThroughTheAsyncProxy() {
		// B7: @Async only takes effect when the call goes through Spring's proxy, not from inside the same bean
		assertThat(AopUtils.isAopProxy(warmer)).isTrue();
	}

	// #2: the test profile loads no data, so health is DOWN; an uptime check on the status code alone must notice
	@Test
	void healthAnswers503WhileNoDataIsLoaded() {
		var health = controller.health();

		assertThat(health.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(health.getBody()).containsEntry("status", "DOWN");
	}

	@Test
	void onlyTheHealthActuatorEndpointIsExposedOverHttp() {
		assertThat(actuatorEndpoints.getAllPaths()).containsExactly("/actuator/health");
	}

}
