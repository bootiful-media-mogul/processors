package com.joshlong.mogul.processors;

import org.jobrunr.scheduling.JobRequestScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.core.GenericHandler;
import org.springframework.integration.dsl.DirectChannelSpec;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.MessageChannels;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * the inbound AMQP side of this module is a router: it takes a request off the wire and
 * enqueues it with JobRunr, and returns. nothing expensive happens on a listener thread.
 * the {@link Processor processors} that do the expensive things run later, on a JobRunr
 * background job server thread, and their results go back out on the reply queue tagged
 * with the correlation id the client sent.
 */
@Configuration
class ProcessorsIntegrationConfiguration {

	private final Logger log = LoggerFactory.getLogger(getClass());

	@Bean
	InitializingBean processorsAmqpInitialization(AmqpAdmin amqpAdmin) {
		return () -> {
			// the api declares these too. both sides do it because either one may be the
			// first to start, and declaration is idempotent.
			this.register(amqpAdmin, ProcessorHeaders.PROCESSOR_REQUESTS);
			this.register(amqpAdmin, ProcessorHeaders.PROCESSOR_REPLIES);
		};
	}

	private void register(AmqpAdmin amqpAdmin, String name) {
		var queue = QueueBuilder.durable(name).build();
		var exchange = ExchangeBuilder.directExchange(name).build();
		amqpAdmin.declareQueue(queue);
		amqpAdmin.declareExchange(exchange);
		amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(name).noargs());
	}

	@Bean
	ProcessorRequestHandler processorRequestHandler(Map<String, Processor> processorsInBeanFactory,
			@Qualifier(ProcessorHeaders.PROCESSOR_REPLIES) MessageChannel replies) {
		this.log.info("the processors available in this node are {}", processorsInBeanFactory.keySet());
		return new ProcessorRequestHandler(processorsInBeanFactory, processorResponse -> {
			var reply = MessageBuilder //
				.withPayload(processorResponse) //
				.setHeader(ProcessorHeaders.PROCESSOR_REQUEST_ID, processorResponse.correlationId()) //
				.setHeader(ProcessorHeaders.PROCESSOR_ID, processorResponse.processorId()) //
				.build();
			replies.send(reply);
		});
	}

	@Bean(name = ProcessorHeaders.PROCESSOR_REPLIES)
	DirectChannelSpec processorReplies() {
		return MessageChannels.direct();
	}

	@Bean
	IntegrationFlow outboundIntegrationFlow(AmqpTemplate template, JsonMapper jsonMapper,
			@Qualifier(ProcessorHeaders.PROCESSOR_REPLIES) MessageChannel channel) {
		var amqpOutboundAdapter = Amqp //
			.outboundAdapter(template) //
			.routingKey(ProcessorHeaders.PROCESSOR_REPLIES);
		return IntegrationFlow//
			.from(channel)//
			.transform(ProcessorResponse.class, jsonMapper::writeValueAsString) //
			.handle(amqpOutboundAdapter)//
			.get();
	}

	@Bean
	IntegrationFlow inboundIntegrationFlow(JsonMapper jsonMapper, ConnectionFactory connectionFactory,
			JobRequestScheduler scheduler) {
		var amqpInboundAdapter = Amqp.inboundAdapter(connectionFactory, ProcessorHeaders.PROCESSOR_REQUESTS);
		return IntegrationFlow//
			.from(amqpInboundAdapter)//
			.handle((GenericHandler<String>) (payload, _) -> {
				var request = jsonMapper.readValue(payload, ProcessorRequest.class);
				this.log.debug("enqueueing [{}] for processor [{}]", request.correlationId(), request.processorId());
				scheduler.enqueue(request);
				return null;
			})//
			.get();
	}

}
