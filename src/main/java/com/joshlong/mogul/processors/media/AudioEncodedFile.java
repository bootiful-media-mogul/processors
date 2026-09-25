package com.joshlong.mogul.processors.media;

import java.io.File;
import java.util.Map;

record AudioEncodedFile(File file, long millisecondsDuration) implements EncodedFile {

	@Override
	public Map<String, Object> context() {
		return Map.of(MediaNormalization.DURATION_IN_MILLISECONDS, this.millisecondsDuration);
	}

}
