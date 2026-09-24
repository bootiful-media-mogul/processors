package com.joshlong.mogul.processors;

import org.jobrunr.scheduling.JobRequestScheduler;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
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
import org.springframework.util.Assert;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Configuration
class ProcessorsIntegrationConfiguration {

    private static final String PROCESSOR_REQUESTS = "processor-requests";

    private static final String PROCESSOR_REPLIES = "processor-replies";

    private static final String PROCESSOR_ID_HEADER = "processor-id";

    private static final String PROCESSOR_REQUEST_ID_HEADER = "processor-request-id";


    @Bean
    ProcessorRequestHandler processorRequestHandler(
            Map<String, Processor> processorsInBeanFactory,
            @Qualifier(PROCESSOR_REPLIES) MessageChannel replies) {
        return new ProcessorRequestHandler(processorsInBeanFactory, processorResponse -> {
            var prm = MessageBuilder
                    .withPayload(processorResponse)
                    .setHeader(PROCESSOR_REQUEST_ID_HEADER, processorResponse.correlationId())
                    .setHeader(PROCESSOR_ID_HEADER, processorResponse.processorId())
                    .build();
            replies.send(prm);
        });
    }

    @Bean(name = PROCESSOR_REQUESTS)
    DirectChannelSpec replies() {
        return MessageChannels.direct();
    }

    @Bean(name = PROCESSOR_REPLIES)
    DirectChannelSpec responses() {
        return MessageChannels.direct();
    }

    @Bean
    IntegrationFlow outboundIntegrationFlow(
            AmqpTemplate template,
            JsonMapper jsonMapper,
            @Qualifier(PROCESSOR_REPLIES) MessageChannel channel) {
        var amqpOutboundAdapter = Amqp.outboundAdapter(template).routingKey(PROCESSOR_REPLIES);
        return IntegrationFlow//
                .from(channel)//
                .transform(ProcessorResponse.class, jsonMapper::writeValueAsString) //
                .handle(amqpOutboundAdapter)//
                .get();
    }

    @Bean
    IntegrationFlow inboundIntegrationFlow(
            JsonMapper jsonMapper,//
            ConnectionFactory connectionFactory,//
            AmqpTemplate template, //
            JobRequestScheduler scheduler //
    ) {
        var amqpInboundAdapter = Amqp.inboundAdapter(connectionFactory, PROCESSOR_REQUESTS);
        return IntegrationFlow//
                .from(amqpInboundAdapter)//
                .handle((GenericHandler<String>) (payload, headers) -> {
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
                })//
                .get();
    }

}
