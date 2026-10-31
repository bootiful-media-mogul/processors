package com.joshlong.mogul.processors.media;

import com.joshlong.mogul.processors.Processor;
import com.joshlong.mogul.processors.ProcessorRequest;
import com.joshlong.mogul.storage.Storage;
import com.joshlong.mogul.utils.*;
import com.joshlong.mogul.utils.ProcessUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * draws the waveform the segment editor shows: one peak per twentieth of a second of the
 * input, in the small binary format {@link AudioWaveform} describes. thea e editor used to
 * download and decode the whole file in the browser to get this, every time it opened.
 * <p>
 * like the other processors it knows nothing about segments or {@code ManagedFile}s:
 * audio comes in from one bucket and key, a waveform goes out to another.
 */
@Component(AudioWaveformProcessor.PROCESSOR_ID)
class AudioWaveformProcessor implements Processor {

	/**
	 * also the bean name, which is what the api addresses its requests to.
	 */
	public static final String PROCESSOR_ID = "audioWaveformProcessor";

	static final int PEAKS_PER_SECOND = 20;

	/**
	 * a waveform needs nowhere near the fidelity of the audio. decoding to 8kHz mono
	 * keeps the intermediate small: an hour is about 58MB of samples instead of 1.2GB.
	 */
	private static final int SAMPLE_RATE = 8000;

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final Storage storage;

	AudioWaveformProcessor(Storage storage) {
		this.storage = storage;
	}

	@Override
	public Map<String, Object> process(ProcessorRequest request) throws Exception {
		var context = request.context();
		var inputBucket = this.required(context, AudioWaveform.INPUT_BUCKET);
		var inputKey = this.required(context, AudioWaveform.INPUT_KEY);
		var outputBucket = this.required(context, AudioWaveform.OUTPUT_BUCKET);
		var outputKey = this.required(context, AudioWaveform.OUTPUT_KEY);
		var inputFilename = (String) context.get(AudioWaveform.INPUT_FILENAME);
		// one directory per job, named for the correlation id, removed at the end: see
		// MediaNormalizationProcessor for why.
		var workspace = new File(System.getProperty("java.io.tmpdir"), "waveform-" + request.correlationId());
		Assert.state(workspace.isDirectory() || workspace.mkdirs(),
				() -> "could not create the workspace [" + workspace + "]");
		try {
			var local = new File(workspace, "input" + extensionFor(inputFilename));
			this.storage.read(inputBucket, inputKey, local);
			this.log.info("drawing the waveform of [{}/{}] into [{}/{}]", inputBucket, inputKey, outputBucket,
					outputKey);
			var waveform = new File(workspace, "waveform.bin");
			var durationInMilliseconds = this.draw(local, new File(workspace, "samples.pcm"), waveform);
			this.storage.write(outputBucket, outputKey, waveform, MediaType.parseMediaType(AudioWaveform.CONTENT_TYPE));
			var response = new HashMap<String, Object>();
			response.put(AudioWaveform.OUTPUT_CONTENT_TYPE, AudioWaveform.CONTENT_TYPE);
			response.put(AudioWaveform.OUTPUT_SIZE, waveform.length());
			response.put(AudioWaveform.DURATION_IN_MILLISECONDS, durationInMilliseconds);
			response.put(AudioWaveform.PEAKS_PER_SECOND, PEAKS_PER_SECOND);
			return response;
		} //
		finally {
			if (!FileUtils.delete(workspace))
				this.log.warn("could not delete the workspace [{}]; this node will leak disk until it restarts",
						workspace.getAbsolutePath());
		}
	}

	/**
	 * decodes {@code audio} to raw samples, then writes its waveform to {@code waveform}.
	 * @return the duration of the audio in milliseconds
	 */
	long draw(File audio, File samples, File waveform) throws IOException, InterruptedException {
		var exit = ProcessUtils.runCommand("ffmpeg", "-y", "-i", audio.getAbsolutePath(), "-vn", "-ac", "1", "-ar",
				Integer.toString(SAMPLE_RATE), "-f", "s16le", "-acodec", "pcm_s16le", samples.getAbsolutePath());
		Assert.state(exit == 0, () -> "the ffmpeg command failed with exit code " + exit);
		return write(samples, waveform);
	}

	/**
	 * reads 16-bit little-endian mono samples and writes the loudest of every
	 * {@code SAMPLE_RATE / PEAKS_PER_SECOND} of them, scaled so the loudest moment in the
	 * file is {@code 255}.
	 */
	static long write(File samples, File waveform) throws IOException {
		var samplesPerPeak = SAMPLE_RATE / PEAKS_PER_SECOND;
		var sampleCount = samples.length() / 2;
		var peakCount = (int) ((sampleCount + samplesPerPeak - 1) / samplesPerPeak);
		var peaks = new int[peakCount];
		var loudest = 0;
		try (var in = new BufferedInputStream(new FileInputStream(samples), 1 << 16)) {
			for (var peak = 0; peak < peakCount; peak++) {
				var max = 0;
				for (var i = 0; i < samplesPerPeak; i++) {
					var low = in.read();
					var high = in.read();
					if (high < 0)
						break;
					var sample = Math.abs((short) ((high << 8) | low));
					if (sample > max)
						max = sample;
				}
				peaks[peak] = max;
				loudest = Math.max(loudest, max);
			}
		}
		var durationInMilliseconds = sampleCount * 1000 / SAMPLE_RATE;
		try (var out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(waveform)))) {
			out.write(AudioWaveform.MAGIC);
			out.writeShort(PEAKS_PER_SECOND);
			out.writeInt((int) durationInMilliseconds);
			out.writeInt(peakCount);
			for (var peak : peaks)
				out.writeByte(loudest == 0 ? 0 : Math.round(peak * 255f / loudest));
		}
		return durationInMilliseconds;
	}

	private static String extensionFor(String filename) {
		if (!StringUtils.hasText(filename))
			return "";
		var period = filename.lastIndexOf('.');
		return period == -1 ? "" : filename.substring(period);
	}

	private String required(Map<String, Object> context, String key) {
		var value = context.get(key);
		Assert.state(value instanceof String s && StringUtils.hasText(s),
				() -> "the request context must carry a '" + key + "'");
		return (String) value;
	}

}
