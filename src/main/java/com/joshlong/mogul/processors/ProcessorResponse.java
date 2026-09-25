package com.joshlong.mogul.processors;

import java.util.Map;

/**
 * what goes back on the reply queue: the request, as it arrived, plus whatever the
 * processor produced, plus whether it worked.
 * <p>
 * the context is echoed rather than reduced on purpose. the client sent that map and is
 * the only one who knows what any of it meant -- keys the processor never looked at are
 * the client's own return address -- so it all comes home, and the client needs nothing
 * but this message to know what just happened.
 *
 * @param error why it failed, if it did. null when {@code success}
 */
public record ProcessorResponse(String processorId, String correlationId, boolean success, String error,
		Map<String, Object> context) {
}
