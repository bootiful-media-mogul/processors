package com.joshlong.mogul.processors;

import org.jobrunr.scheduling.JobRequestScheduler;
import org.jspecify.annotations.Nullable;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.core.GenericHandler;
import org.springframework.integration.dsl.DirectChannelSpec;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.MessageChannels;
import org.springframework.messaging.MessageHeaders;
import org.springframework.util.Assert;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Configuration
class ProcessorsIntegrationFlowConfiguration {

    private static final String PROCESSOR_REQUESTS = "processor-requests";

    private static final String PROCESSOR_REPLIES = "processor-replies";

    private static final String PROCESSOR_ID_HEADER = "processor-id";

    private static final String PROCESSOR_REQUEST_ID_HEADER = "processor-request-id";

    @Bean(name = PROCESSOR_REPLIES)
    DirectChannelSpec responses() {
        return MessageChannels.direct();
    }

    @Bean
    IntegrationFlow inboundIntegrationFlow(JsonMapper jsonMapper, ConnectionFactory connectionFactory, AmqpTemplate template,
                                           JobRequestScheduler scheduler) {
        var amqpInboundAdapter = Amqp.inboundAdapter(connectionFactory, PROCESSOR_REQUESTS);
        var amqpOutboundAdapter = Amqp.outboundAdapter(template).routingKey(PROCESSOR_REPLIES);
        return IntegrationFlow//
                .from(amqpInboundAdapter)//
                .handle(new GenericHandler<String>() {
                    @Override
                    public @Nullable Object handle(String payload, MessageHeaders headers) {
                        for (var h : new String[]{PROCESSOR_ID_HEADER, PROCESSOR_REQUEST_ID_HEADER})
                            Assert.state(headers.containsKey(h), "no header '" + h + "' found!");
                        var processorId = headers.get(PROCESSOR_ID_HEADER, String.class);
                        var requestId = headers.get(PROCESSOR_REQUEST_ID_HEADER, String.class);
                        // @formatter:on
                        var map = jsonMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
                        });
                        // @formatter:off
                        var pr = new ProcessorRequest(processorId, requestId, map);
                        scheduler.enqueue(pr);
                        return null;
                    }
                })//
                .handle(amqpOutboundAdapter)
                .get();
    }

}
