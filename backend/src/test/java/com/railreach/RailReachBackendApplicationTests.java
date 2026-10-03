package com.railreach;

import com.railreach.service.ReachabilityWarmer;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RailReachBackendApplicationTests {

	@Autowired
	ReachabilityWarmer warmer;

	@Test
	void contextLoads() {
	}

	@Test
	void warmingIsCalledThroughTheAsyncProxy() {
		// B7: @Async only takes effect when the call goes through Spring's proxy, not from inside the same bean
		assertThat(AopUtils.isAopProxy(warmer)).isTrue();
	}

}
