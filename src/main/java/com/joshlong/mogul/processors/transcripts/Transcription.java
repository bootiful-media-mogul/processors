package com.joshlong.mogul.processors.transcripts;

/**
 * the wire contract for {@link TranscriptionProcessor}. the api writes these keys into
 * the request context and reads them back off the reply; it has its own copy of these
 * names because the two modules are separately deployed and share no code.
 */
abstract class Transcription {

	// what the api sends us
	static final String INPUT_BUCKET = "inputBucket";

	static final String INPUT_KEY = "inputKey";

	// what we send back. the whole text: an hour of speech is tens of kilobytes, which
	// is nothing to a queue, and the alternative -- writing it to storage and replying
	// with a key -- would make the api go and fetch what it is about to write to a
	// column anyway.
	static final String TRANSCRIPT = "transcript";

}
