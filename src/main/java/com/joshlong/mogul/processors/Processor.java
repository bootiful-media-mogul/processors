package com.joshlong.mogul.processors;

import java.util.Map;

/**
 * does the actual, long-running, CPU-monopolizing work. implementations are ordinary
 * Spring beans and are looked up by their bean name, which is the
 * {@link ProcessorRequest#processorId() processorId} the client sent.
 */
public interface Processor {

	/**
	 * @param request the request, whose {@link ProcessorRequest#context() context}
	 * carries everything -- and only what -- the processor needs to do its job
	 * @return whatever the client needs in order to make sense of the result. it is sent
	 * back verbatim on the reply queue as the {@link ProcessorResponse#context()}
	 */
	Map<String, Object> process(ProcessorRequest request) throws Exception;

}
