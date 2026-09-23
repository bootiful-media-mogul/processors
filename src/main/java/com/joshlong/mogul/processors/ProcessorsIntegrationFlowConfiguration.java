package com.joshlong.mogul.processors;

import org.jspecify.annotations.Nullable;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.core.GenericHandler;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.messaging.MessageHeaders;

@Configuration
class ProcessorsIntegrationFlowConfiguration {

	private static final String PROCESSOR_REQUESTS = "processor-requests";

	private static final String PROCESSOR_REPLIES = "processor-replies";

	@Bean
	IntegrationFlow mediaProcessorIntegrationFlow(ConnectionFactory connectionFactory) {
		var amqpInboundAdapter = Amqp.inboundAdapter(connectionFactory, PROCESSOR_REQUESTS);
		return IntegrationFlow.from(amqpInboundAdapter).handle(new GenericHandler<Object>() {
			@Override
			public @Nullable Object handle(Object payload, MessageHeaders headers) {
				IO.println("got a request!");
				return null;
			}
		}).get();
	}

}
