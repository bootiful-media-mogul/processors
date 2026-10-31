package com.joshlong.mogul.processors.media;

/**
 * the wire contract for {@link CleanedAudioIngestionProcessor}. the api has its own copy
 * of these names; the two modules share no code.
 */
abstract class CleanedAudioIngestion {

	// what the api sends us: two urls cleanvoice handed back, and where to put the audio
	static final String AUDIO_URL = "cleanedAudioUrl";

	static final String LABELS_URL = "cleanedLabelsUrl";

	static final String OUTPUT_BUCKET = "outputBucket";

	static final String OUTPUT_KEY = "outputKey";

	// what we send back
	static final String OUTPUT_CONTENT_TYPE = "outputContentType";

	static final String DURATION_IN_MILLISECONDS = "durationInMilliseconds";

	/**
	 * json: {@code {"cleanvoice": [{"start": ms, "end": ms, "kind": "FILLER_SOUND"}],
	 * "silences": [{"start": ms, "end": ms}]}}. cleanvoice's kinds have its {@code MUTE_}
	 * prefix taken off.
	 */
	static final String DETECTIONS = "detections";

	static final String CONTENT_TYPE = "audio/flac";

}
