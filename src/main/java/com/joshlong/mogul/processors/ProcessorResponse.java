package com.joshlong.mogul.processors;

import java.util.Map;

public record ProcessorResponse(String processorId, String correlationId, boolean success,
		Map<String, Object> context) {
}
