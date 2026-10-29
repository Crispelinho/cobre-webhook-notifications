package com.cobre.notifications.shared.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one clock of the application. Capabilities receive it as a dependency, so tests can replace it in one place.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
