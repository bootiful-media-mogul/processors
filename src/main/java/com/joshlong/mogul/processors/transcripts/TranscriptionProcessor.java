package com.joshlong.mogul.processors.transcripts;

import com.joshlong.mogul.processors.Processor;
import com.joshlong.mogul.processors.ProcessorRequest;
import com.joshlong.mogul.storage.Storage;
import com.joshlong.mogul.utils.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.Map;

/**
 * turns one audio object into text.
 * <p>
 * this is the most expensive thing the system does, and it was the last of it still
 * running inside the api: the whole episode came down over the network into the api's own
 * ephemeral storage, a full {@code ffmpeg} decode walked it looking for silence to cut
 * on, a cut was forked per chunk, and then it sat on the model calls for as long as those
 * took. the download and the decode are why it belongs here; the model calls are why it
 * takes as long as it does either way.
 * <p>
 * everything it needs arrives in the {@link ProcessorRequest#context() context}: one
 * bucket and one key. it knows nothing about transcripts, segments, or moguls -- the api
 * holds all of that and reassembles it when the reply comes back bearing the correlation
 * id.
 */
@Component(TranscriptionProcessor.PROCESSOR_ID)
class TranscriptionProcessor implements Processor {

	/**
	 * also the bean name, which is what the api addresses its requests to.
	 */
	public static final String PROCESSOR_ID = "audioTranscriptionProcessor";

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final Storage storage;

	private final Transcriber transcriber;

	TranscriptionProcessor(Storage storage, Transcriber transcriber) {
		this.storage = storage;
		this.transcriber = transcriber;
	}

	@Override
	public Map<String, Object> process(ProcessorRequest request) throws Exception {
		var context = request.context();
		var inputBucket = this.required(context, Transcription.INPUT_BUCKET);
		var inputKey = this.required(context, Transcription.INPUT_KEY);

		// one directory per job: the episode, every chunk cut out of it, and the file
		// ffmpeg's silence detection writes its findings into. named for the correlation
		// id, which is already a uuid, so it needs no temp file of its own to find a
		// free name with.
		var workspace = new File(System.getProperty("java.io.tmpdir"), "transcription-" + request.correlationId());
		Assert.state(workspace.isDirectory() || workspace.mkdirs(),
				() -> "could not create the workspace [" + workspace + "]");
		try {
			// ffmpeg decides what it is looking at by probing, but SilenceDetector and
			// the chunker both write siblings of this file, so it wants a name.
			var local = new File(workspace, "audio.mp3");
			this.storage.read(inputBucket, inputKey, local);
			this.log.info("transcribing [{}/{}] ({} bytes)", inputBucket, inputKey, local.length());
			var transcript = this.transcriber.transcribe(workspace, local);
			this.log.debug("transcribed [{}/{}] into {} characters", inputBucket, inputKey, transcript.length());
			return Map.of(Transcription.TRANSCRIPT, transcript);
		} //
		finally {
			if (FileUtils.delete(workspace))
				this.log.debug("deleted the workspace [{}] after transcription", workspace.getAbsolutePath());
			else
				this.log.warn("could not delete the workspace [{}]; this node will leak disk until it restarts",
						workspace.getAbsolutePath());
		}
	}

	private String required(Map<String, Object> context, String key) {
		var value = context.get(key);
		Assert.state(value instanceof String s && StringUtils.hasText(s),
				() -> "the request context must carry a '" + key + "'");
		return (String) value;
	}

}
