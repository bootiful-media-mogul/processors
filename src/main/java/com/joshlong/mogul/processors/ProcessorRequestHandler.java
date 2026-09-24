package com.joshlong.mogul.processors;

import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.util.Assert;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ProcessorRequestHandler implements JobRequestHandler<ProcessorRequest> {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Map<String, Processor> processors;

    private final Consumer<ProcessorResponse> consumer;

    public ProcessorRequestHandler(Map<String, Processor> processors,
                                   Consumer<ProcessorResponse> consumer) {
        this.processors = processors;
        this.consumer = consumer;
    }

    @Override
    public void run(ProcessorRequest jobRequest) throws Exception {
        Assert.state(this.processors.containsKey(jobRequest.processorId()),
                "there's no processor called " + jobRequest.processorId() + "!");
        this.log.info("processing job request {}", jobRequest);
        var processor = this.processors.get(jobRequest.processorId());
        this.log.info("found processor {}. invoking {}", jobRequest.processorId(), processor.getClass().getName());
        var context = new HashMap<String, Object>();
        context.putAll(jobRequest.context());
        try {
            processor.process(jobRequest);
            var processorResponse = new ProcessorResponse(jobRequest.processorId(), jobRequest.correlationId(),
                    true, context);
            this.consumer.accept(processorResponse);
        }//
        catch (Exception e) {
            context.put("exception", this.exceptionMessage(e));
            this.log.error("error processing job request {}", jobRequest, e);
            var processorResponse = new ProcessorResponse(jobRequest.processorId(), jobRequest.correlationId(),
                    false, context);
            this.consumer.accept(processorResponse);
        }
    }

    private String exceptionMessage(Throwable e) {
        var rootCause = NestedExceptionUtils.getRootCause(e);
        return rootCause != null ? rootCause.getMessage() : e.getMessage();
    }

}
