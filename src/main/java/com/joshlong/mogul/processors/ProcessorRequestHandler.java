package com.joshlong.mogul.processors;

import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.util.Assert;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * where the work actually happens. the AMQP listener is only a dispatcher: it hands the
 * request to JobRunr, and JobRunr hands it back here, on a background job server thread
 * whose pool size is what bounds how many {@code ffmpeg} and {@code magick} subprocesses
 * this node will fork at once.
 * <p>
 * if this throws, or the node dies mid-job, no reply is sent -- and a client that assumes
 * nothing is done until it hears that it is has exactly the right answer.
 */
public class ProcessorRequestHandler implements JobRequestHandler<ProcessorRequest> {

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
			var result = processor.process(jobRequest);
			this.reply(jobRequest, true, null, result);
		} //
		catch (Throwable throwable) {
			this.log.error("error processing job request [{}]", jobRequest, throwable);
			this.reply(jobRequest, false, this.errorMessage(throwable), Map.of());
		}
	}

	private void reply(ProcessorRequest request, boolean success, String error, Map<String, Object> result) {
		// the request comes home with the reply, so the client doesn't have to have kept
		// anything. what the processor produced goes on top of it.
		var context = new HashMap<>(request.context());
		if (result != null)
			context.putAll(result);
		this.replies
			.accept(new ProcessorResponse(request.processorId(), request.correlationId(), success, error, context));
	}

	private String errorMessage(Throwable e) {
		var rootCause = NestedExceptionUtils.getRootCause(e);
		var message = rootCause != null ? rootCause.getMessage() : e.getMessage();
		return message == null ? e.getClass().getName() : message;
	}

}
