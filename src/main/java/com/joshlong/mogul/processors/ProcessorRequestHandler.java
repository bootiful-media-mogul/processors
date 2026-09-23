package com.joshlong.mogul.processors;

import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.springframework.stereotype.Component;

@Component
public class ProcessorRequestHandler implements JobRequestHandler<ProcessorRequest> {

	@Override
	public void run(ProcessorRequest jobRequest) throws Exception {

	}

}
