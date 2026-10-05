package com.railreach;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// FeedUpdateCenter's daily check of the GTFS zips
@EnableScheduling
public class RailReachBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(RailReachBackendApplication.class, args);
	}

}
