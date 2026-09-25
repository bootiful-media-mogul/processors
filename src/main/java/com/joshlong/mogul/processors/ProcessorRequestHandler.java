package com.joshlong.mogul.processors;

import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.util.Assert;

import java.util.Map;
import java.util.function.Consumer;

/**
 * where the work actually happens. the AMQP listener is only a dispatcher: it hands the
 * request to JobRunr, and JobRunr hands it back here, on a background job server thread
 * whose pool size is what bounds how many {@code ffmpeg} and {@code magick} subprocesses
 * this node will fork at once.
 */
public class ProcessorRequestHandler implements JobRequestHandler<ProcessorRequest> {

	/**
	 * the key under which a failed request reports why it failed.
	 */
	public static final String EXCEPTION = "exception";

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final Map<String, Processor> processors;

	private final Consumer<ProcessorResponse> replies;

	public ProcessorRequestHandler(Map<String, Processor> processors, Consumer<ProcessorResponse> replies) {
		this.processors = processors;
		this.replies = replies;
	}

	@Override
	public void run(ProcessorRequest jobRequest) {
		var processorId = jobRequest.processorId();
		Assert.state(this.processors.containsKey(processorId), "there's no processor called " + processorId + "!");
		var processor = this.processors.get(processorId);
		this.log.info("found processor [{}]. invoking [{}] for request [{}]", processorId,
				processor.getClass().getName(), jobRequest.correlationId());
		try {
			// only what the processor produced goes back on the wire. the client already
			// knows what it sent, and it has the correlationId with which to look it up.
			var result = processor.process(jobRequest);
			this.reply(jobRequest, true, result == null ? Map.of() : result);
		} //
		catch (Throwable throwable) {
			this.log.error("error processing job request [{}]", jobRequest, throwable);
			this.reply(jobRequest, false, Map.of(EXCEPTION, this.exceptionMessage(throwable)));
		}
	}

	private void reply(ProcessorRequest request, boolean success, Map<String, Object> context) {
		this.replies.accept(new ProcessorResponse(request.processorId(), request.correlationId(), success, context));
	}

	private String exceptionMessage(Throwable e) {
		var rootCause = NestedExceptionUtils.getRootCause(e);
		var message = rootCause != null ? rootCause.getMessage() : e.getMessage();
		return message == null ? e.getClass().getName() : message;
	}

}
