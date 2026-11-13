package com.cobre.notifications.shared.observability;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Turns on W3C trace-context propagation. With the OpenTelemetry starter alone the application registers a no-op
 * propagator, so no {@code traceparent} is read from incoming requests or written into Kafka record headers and every
 * hop would start a new trace. Only the trace context is propagated, no baggage.
 */
@Configuration(proxyBeanMethods = false)
class TracePropagationConfiguration {

	@Bean
	TextMapPropagator w3cTraceContextPropagator() {
		return W3CTraceContextPropagator.getInstance();
	}

}
