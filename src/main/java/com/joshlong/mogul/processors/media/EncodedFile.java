package com.joshlong.mogul.processors.media;

import java.io.File;
import java.util.Map;

/**
 * the result of an {@link Encoder#encode(File)}: the newly written file, and whatever the
 * encoder learned about it along the way that the client will want.
 */
interface EncodedFile {

	File file();

	Map<String, Object> context();

}
