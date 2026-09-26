package com.joshlong.mogul.processors.transcripts;

import java.io.File;

/**
 * given an audio file, return a textual transcript of it.
 */
interface Transcriber {

	/**
	 * @param workspace a directory this call may write whatever it likes into. the caller
	 * created it and the caller deletes it; nothing here has to clean up after itself.
	 * @param audio the audio to transcribe, already on local disk
	 */
	String transcribe(File workspace, File audio);

}
