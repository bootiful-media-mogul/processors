package com.joshlong.mogul.processors.media;

/**
 * the wire contract for {@link AudioProductionProcessor}. the api writes these keys into
 * the request context and reads them back off the reply; it has its own copy of these
 * names because the two modules are separately deployed and share no code.
 */
abstract class AudioProduction {

	// what the api sends us. the two lists are parallel and ordered: element n of each
	// describes the nth thing to play. order is the whole point -- an episode is its
	// intro, then its interview, then its bumper -- so this is a list and not a set.
	static final String INPUT_BUCKETS = "inputBuckets";

	static final String INPUT_KEYS = "inputKeys";

	static final String OUTPUT_BUCKET = "outputBucket";

	static final String OUTPUT_KEY = "outputKey";

	// what we send back
	static final String OUTPUT_CONTENT_TYPE = "outputContentType";

	static final String OUTPUT_SIZE = "outputSize";

	/**
	 * the same key {@link MediaNormalization} uses, and deliberately so: it is the
	 * {@link AudioEncoder} that reports it, whichever processor asked for the encode.
	 */
	static final String DURATION_IN_MILLISECONDS = MediaNormalization.DURATION_IN_MILLISECONDS;

}
