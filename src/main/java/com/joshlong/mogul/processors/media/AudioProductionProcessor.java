package com.joshlong.mogul.processors.media;

import com.joshlong.mogul.processors.Processor;
import com.joshlong.mogul.processors.ProcessorRequest;
import com.joshlong.mogul.storage.Storage;
import com.joshlong.mogul.utils.FileUtils;
import com.joshlong.mogul.utils.ProcessUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * glues an ordered run of audio objects together into the one file that gets published: a
 * podcast episode's intro, interview, and bumper, in that order, as a single mp3. this is
 * the {@code ffmpeg} work that used to run inside the api, where one publication could
 * hold a machine that was also trying to serve GraphQL requests.
 * <p>
 * everything it needs arrives in the {@link ProcessorRequest#context() context}: the
 * buckets and keys to read, in the order they should play, and the bucket and key to
 * write. it knows nothing about episodes, segments, or moguls -- the api holds all of
 * that and reassembles it when the reply comes back bearing the correlation id.
 */
@Component(AudioProductionProcessor.PROCESSOR_ID)
class AudioProductionProcessor implements Processor {

	/**
	 * also the bean name, which is what the api addresses its requests to.
	 */
	public static final String PROCESSOR_ID = "audioProductionProcessor";

	private static final String MPEG = "audio/mpeg";

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final Storage storage;

	private final AudioEncoder audioEncoder;

	AudioProductionProcessor(Storage storage, AudioEncoder audioEncoder) {
		this.storage = storage;
		this.audioEncoder = audioEncoder;
	}

	@Override
	public Map<String, Object> process(ProcessorRequest request) throws Exception {
		var context = request.context();
		var buckets = this.requiredList(context, AudioProduction.INPUT_BUCKETS);
		var keys = this.requiredList(context, AudioProduction.INPUT_KEYS);
		Assert.state(buckets.size() == keys.size(), () -> "there are " + buckets.size() + " input buckets but "
				+ keys.size() + " input keys; they describe the same list and must agree");
		var outputBucket = this.required(context, AudioProduction.OUTPUT_BUCKET);
		var outputKey = this.required(context, AudioProduction.OUTPUT_KEY);

		// one directory per job, and everything -- the downloads, the uncompressed
		// intermediates, the join, the encode -- happens inside it, so there is exactly
		// one thing to delete at the end. production is the heaviest user of ephemeral
		// storage this workload has: every input is expanded to uncompressed wav before
		// it can be joined, and Autopilot caps the pod at 10Gi.
		//
		// it is named for the correlation id, which is already a uuid, so it needs no
		// temp file of its own to find a free name with.
		var workspace = new File(System.getProperty("java.io.tmpdir"), "production-" + request.correlationId());
		Assert.state(workspace.isDirectory() || workspace.mkdirs(),
				() -> "could not create the workspace [" + workspace + "]");
		try {
			var inputs = new ArrayList<File>();
			for (var i = 0; i < keys.size(); i++) {
				// no extension: ffmpeg decides what it is looking at by probing the
				// bytes, and these objects are named for their managed file, not for
				// their type.
				var local = new File(workspace, "input-" + i);
				this.storage.read(buckets.get(i), keys.get(i), local);
				inputs.add(local);
			}
			this.log.info("producing {} input(s) into [{}/{}]", inputs.size(), outputBucket, outputKey);
			var encoded = this.audioEncoder.encode(this.join(workspace, inputs));
			this.storage.write(outputBucket, outputKey, encoded.file(), MediaType.parseMediaType(MPEG));
			var response = new HashMap<String, Object>(encoded.context());
			response.put(AudioProduction.OUTPUT_CONTENT_TYPE, MPEG);
			response.put(AudioProduction.OUTPUT_SIZE, encoded.file().length());
			return response;
		} //
		finally {
			if (FileUtils.delete(workspace))
				this.log.debug("deleted the workspace [{}] after audio production", workspace.getAbsolutePath());
			else
				this.log.warn("could not delete the workspace [{}]; this node will leak disk until it restarts",
						workspace.getAbsolutePath());
		}
	}

	/**
	 * the concat demuxer copies streams rather than re-encoding them, which is what makes
	 * joining an hour of audio cheap -- and which also means every input has to already
	 * be the same kind of thing. so each one is rewritten as wav at a fixed rate and
	 * channel count first.
	 */
	private File join(File workspace, List<File> inputs) throws Exception {
		var listing = new StringBuilder();
		// deliberately sequential. the old in-api version forked one ffmpeg per input at
		// once; here the ceiling on concurrent work is the job server's worker count,
		// and a job that fans out inside itself walks straight through it.
		for (var input : inputs) {
			Assert.state(input.exists() && input.isFile(),
					() -> "the input [" + input.getAbsolutePath() + "] must be a valid, existing file");
			listing.append("file '")
				.append(this.wav(workspace, input).getAbsolutePath())
				.append("'")
				.append(System.lineSeparator());
		}
		var manifest = this.workspaceFile(workspace, "txt");
		try (var out = new FileWriter(manifest)) {
			FileCopyUtils.copy(listing.toString(), out);
		}
		var joined = this.workspaceFile(workspace, "wav");
		var exit = ProcessUtils.runCommand("ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i",
				manifest.getAbsolutePath(), "-c", "copy", joined.getAbsolutePath());
		Assert.state(exit == 0, () -> "the ffmpeg concat command failed with exit code " + exit);
		Assert.state(joined.exists(), () -> "the joined audio [" + joined.getAbsolutePath() + "] was not written");
		return joined;
	}

	private File wav(File workspace, File input) throws Exception {
		var wav = this.workspaceFile(workspace, "wav");
		var exit = ProcessUtils.runCommand("ffmpeg", "-y", "-i", input.getAbsolutePath(), "-vn", "-acodec", "pcm_s16le",
				"-ar", "48000", "-ac", "2", "-f", "wav", wav.getAbsolutePath());
		Assert.state(exit == 0, () -> "the ffmpeg command failed with exit code " + exit);
		return wav;
	}

	private File workspaceFile(File workspace, String extension) {
		return new File(workspace, UUID.randomUUID() + "." + extension);
	}

	private List<String> requiredList(Map<String, Object> context, String key) {
		var value = context.get(key);
		Assert.state(value instanceof List<?> l && !l.isEmpty(),
				() -> "the request context must carry a non-empty '" + key + "'");
		var values = new ArrayList<String>();
		for (var each : (List<?>) value) {
			Assert.state(each instanceof String s && StringUtils.hasText(s),
					() -> "every entry in '" + key + "' must be a non-empty string");
			values.add((String) each);
		}
		return values;
	}

	private String required(Map<String, Object> context, String key) {
		var value = context.get(key);
		Assert.state(value instanceof String s && StringUtils.hasText(s),
				() -> "the request context must carry a '" + key + "'");
		return (String) value;
	}

}
