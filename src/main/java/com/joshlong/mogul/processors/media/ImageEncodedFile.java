package com.joshlong.mogul.processors.media;

import java.io.File;
import java.util.Map;

record ImageEncodedFile(File file) implements EncodedFile {

	@Override
	public Map<String, Object> context() {
		return Map.of();
	}

}
