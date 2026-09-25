package com.joshlong.mogul.processors.media;

/**
 * the wire contract for {@link MediaNormalizationProcessor}. the api writes these keys
 * into the request context and reads them back off the reply; it has its own copy of
 * these names because the two modules are separately deployed and share no code.
 */
abstract class MediaNormalization {

	// what the api sends us
	static final String INPUT_BUCKET = "inputBucket";

	static final String INPUT_KEY = "inputKey";

	static final String INPUT_FILENAME = "inputFilename";

	static final String INPUT_CONTENT_TYPE = "inputContentType";

	static final String OUTPUT_BUCKET = "outputBucket";

	static final String OUTPUT_KEY = "outputKey";

	// what we send back
	static final String OUTPUT_CONTENT_TYPE = "outputContentType";

	static final String OUTPUT_SIZE = "outputSize";

	static final String DURATION_IN_MILLISECONDS = "durationInMilliseconds";

}
