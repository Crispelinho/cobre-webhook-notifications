package com.cobre.notifications.notifications.adapters.out.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the dispatch topic; {@code KafkaAdmin} creates it when the application starts.
 */
@Configuration(proxyBeanMethods = false)
class DispatchTopicConfiguration {

	@Bean
	NewTopic dispatchTopic(@Value("${app.dispatch.partitions:3}") int partitions) {
		return TopicBuilder.name(KafkaDispatchPublisher.TOPIC).partitions(partitions).build();
	}

}
