package com.joshlong.mogul.processors;

import org.jobrunr.jobs.lambdas.JobRequest;
import org.jobrunr.jobs.lambdas.JobRequestHandler;

import java.util.Map;

public record ProcessorRequest(String processorId, String correlationId,
		Map<String, Object> context) implements JobRequest {

	@Override
	public Class<? extends JobRequestHandler<?>> getJobRequestHandler() {
		return ProcessorRequestHandler.class;
	}

}
