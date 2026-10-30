package com.joshlong.mogul.processors.media;

/**
 * the wire contract for {@link AudioWaveformProcessor}, and the format of the file it
 * writes. the api writes these keys into the request context and reads them back off the
 * reply; it has its own copy of these names because the two modules are separately
 * deployed and share no code.
 * <p>
 * the file is big-endian: the four ascii bytes {@code MWF1} (the trailing {@code 1} is
 * the format version), a {@code uint16} of peaks per second, a {@code uint32} duration in
 * milliseconds, a {@code uint32} count of peaks, and then that many bytes, each one peak
 * from {@code 0} (silence) to {@code 255} (the loudest moment in the file).
 */
abstract class AudioWaveform {

	// what the api sends us
	static final String INPUT_BUCKET = "inputBucket";

	static final String INPUT_KEY = "inputKey";

	static final String INPUT_FILENAME = "inputFilename";

	static final String OUTPUT_BUCKET = "outputBucket";

	static final String OUTPUT_KEY = "outputKey";

	// what we send back
	static final String OUTPUT_CONTENT_TYPE = "outputContentType";

	static final String OUTPUT_SIZE = "outputSize";

	static final String DURATION_IN_MILLISECONDS = "durationInMilliseconds";

	static final String PEAKS_PER_SECOND = "peaksPerSecond";

	// the file
	static final byte[] MAGIC = { 'M', 'W', 'F', '1' };

	static final String CONTENT_TYPE = "application/octet-stream";

}
