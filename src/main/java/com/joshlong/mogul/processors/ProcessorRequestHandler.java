package com.joshlong.mogul.processors;

import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.Map;

public class ProcessorRequestHandler implements JobRequestHandler<ProcessorRequest> {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Map<String, Processor> processorMap;

    private final MessageChannel responses;

    public ProcessorRequestHandler(MessageChannel responses, Map<String, Processor> processorMap) {
        this.processorMap = processorMap;
        this.responses = responses;
    }

    @Override
    public void run(ProcessorRequest jobRequest) throws Exception {
        Assert.state(this.processorMap.containsKey(jobRequest.processorId()),
                "there's no processor called " + jobRequest.processorId() + "!");
        this.log.info("processing job request {}", jobRequest);
        var processor = this.processorMap.get(jobRequest.processorId());
        this.log.info("found processor {}. invoking {}", jobRequest.processorId(), processor.getClass().getName());
        processor.process(jobRequest);
        // todo handle the response and sending outbound reply
//        this.responses.send( MessageChannel.);
    }

}
