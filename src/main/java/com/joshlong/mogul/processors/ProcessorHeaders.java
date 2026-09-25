package com.joshlong.mogul.processors;

/**
 * the AMQP contract shared with the {@code api} module. the api writes requests onto
 * {@link #PROCESSOR_REQUESTS} and reads replies off {@link #PROCESSOR_REPLIES}; the
 * headers let a consumer decide whether a message is interesting before it pays to
 * deserialize the body.
 */
public abstract class ProcessorHeaders {

	public static final String PROCESSOR_REQUESTS = "processor-requests";

	public static final String PROCESSOR_REPLIES = "processor-replies";

	/**
	 * the name of the {@link Processor} bean that should handle -- or that did handle --
	 * this message.
	 */
	public static final String PROCESSOR_ID = "processor-id";

	/**
	 * echoed back on the reply so that the client can tie a response to the request it
	 * made.
	 */
	public static final String PROCESSOR_REQUEST_ID = "processor-request-id";

}
