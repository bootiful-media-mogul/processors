package com.joshlong.mogul.processors.transcripts;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.Assert;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * this is an implementation of {@link Transcriber} that divides larger files into smaller
 * ones and then transcribes each of those, aggregating all the results into one big
 * transcript. it also takes care to try to divine pauses - gaps of silence - in the audio
 * and cut along those gaps.
 *
 * @author Josh Long
 */
class ChunkingTranscriber implements Transcriber {

	private static final ThreadLocal<NumberFormat> NUMBER_FORMAT = new ThreadLocal<>();

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final RetryTemplate retryTemplate;

	private final OpenAiAudioTranscriptionModel openAiAudioTranscriptionModel;

	private final long maxFileSize;

	ChunkingTranscriber(OpenAiAudioTranscriptionModel openAiAudioTranscriptionModel, RetryTemplate retryTemplate,
			long maxFileSizeInBytes) {
		this.retryTemplate = retryTemplate;
		this.openAiAudioTranscriptionModel = openAiAudioTranscriptionModel;
		this.maxFileSize = maxFileSizeInBytes;
		Assert.notNull(this.openAiAudioTranscriptionModel, "the openAiAudioTranscriptionModel must not be null");
		Assert.state(this.maxFileSize > 0, "the max file size must be greater than zero");
	}

	private static String convertMillisToTimeFormat(long millis) {
		var hours = TimeUnit.MILLISECONDS.toHours(millis);
		var minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
		var seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
		var milliseconds = millis % 1000;
		return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds);
	}

	private static <T> T from(Future<T> tFuture) {
		try {
			return tFuture.get();
		} //
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	static void bisect(File source, File destination, long start, long stop) throws IOException, InterruptedException {
		var process = new ProcessBuilder()
			.command("ffmpeg", "-i", source.getAbsolutePath(), "-ss", convertMillisToTimeFormat(start), "-to",
					convertMillisToTimeFormat(stop), "-c", "copy", destination.getAbsolutePath())
			.redirectErrorStream(true)
			.start();
		var output = drain(process);
		var exitCode = process.waitFor();
		Assert.state(exitCode == 0, () -> "the result must be a zero exit code, but was [" + exitCode + "]: " + output);
	}

	// ffmpeg writes its banner and its progress to stderr, and a pipe that nobody
	// drains fills up and blocks the child forever -- so read to EOF *before*
	// waiting. EOF arrives when ffmpeg exits, so the wait below is then immediate.
	// the output is kept only to explain a failure; a stream copy says very little.
	private static @NonNull String drain(Process process) throws IOException {
		var output = (String) null;
		try (var stdout = process.getInputStream()) {
			output = new String(stdout.readAllBytes(), StandardCharsets.UTF_8);
		}
		return output;
	}

	@Override
	public String transcribe(File workspace, File audio) {
		try {
			var orderedAudio = this.divide(workspace, audio)//
				.stream()//
				.map(segment -> (Callable<String>) () -> {
					var audioResource = new FileSystemResource(segment.audio());
					try {
						return this.retryTemplate.execute(() -> {
							this.log.debug("start transcribe audio resource {}", audioResource);
							var apt = new AudioTranscriptionPrompt(audioResource);
							var result = this.openAiAudioTranscriptionModel.call(apt);
							this.log.debug("finish transcribe audio result {}", result);
							return result.getResult().getOutput();
						});
					} //
					catch (Throwable e) {
						// this will capture RetryException as thrown by RetryTemplate
						var formatted = "oops! an error when trying to process a %s # %s"
							.formatted(TranscriptionSegment.class.getName(), audioResource.getFilename());
						this.log.error(formatted, e);
					}
					return "";
				})//
				.toList();
			// the chunks go out at once, and on purpose: this is the one part of the
			// pipeline that is neither cpu nor disk but a queue of http calls waiting on
			// a model. the ffmpeg passes above it are what the job server's worker count
			// is protecting, and those are strictly sequential.
			return this.executor//
				.invokeAll(orderedAudio)//
				.stream()//
				.map(ChunkingTranscriber::from)//
				.collect(Collectors.joining());
		} //
		catch (Exception e) {
			this.log.error("trouble trying to transcode!", e);
			throw new RuntimeException(e);
		}
	}

	private java.util.List<TranscriptionSegment> divide(File workspace, File originalAudio) throws Exception {
		Assert.state(originalAudio.exists() && originalAudio.isFile(),
				() -> "the audio [" + originalAudio.getAbsolutePath() + "] must be a valid, existing file");
		var duration = durationFor(originalAudio);
		var sizeInBytes = originalAudio.length();

		// special case if the file is small enough
		if (sizeInBytes < this.maxFileSize)
			return java.util.List.of(new TranscriptionSegment(originalAudio, 0, 0, duration.toMillis()));

		// 1. find duration/size of the file
		// 2. find gaps/silence in the audio file.
		// 3. find the gap in the file nearest to the appropriate divided timecode
		// 4. divide the file into 20mb chunks.

		// 2. find gaps/silence in the audio file.
		var silentGapsInAudio = SilenceDetector.detect(originalAudio);

		// 3. find the gap in the file nearest to the appropriate divided timecode
		var parts = (int) (sizeInBytes <= this.maxFileSize ? 1 : sizeInBytes / this.maxFileSize);
		if (sizeInBytes % this.maxFileSize != 0) {
			parts += 1;
		}

		Assert.state(parts > 0, "there can not be zero parts. this won't work!");
		var totalDurationInMillis = duration.toMillis();
		var durationOfASinglePart = totalDurationInMillis / parts; // 724333
		var rangesOfSilence = new long[1 + parts];

		rangesOfSilence[0] = 0;

		for (var indx = 1; indx < rangesOfSilence.length; indx++)
			rangesOfSilence[indx] = (indx) * durationOfASinglePart;

		rangesOfSilence[rangesOfSilence.length - 1] = totalDurationInMillis;

		Assert.state(rangesOfSilence[rangesOfSilence.length - 1] + durationOfASinglePart >= totalDurationInMillis,
				"the last silence marker (plus individual duration of " + durationOfASinglePart
						+ " ) should be greater than (or at least equal to) the total duration of the entire audio clip, "
						+ durationOfASinglePart);

		var ranges = new ArrayList<float[]>();
		for (var i = 1; i < rangesOfSilence.length; i += 1) {
			var range = new float[] { rangesOfSilence[i - 1], rangesOfSilence[i] };
			ranges.add(range);
		}

		var betterRanges = new ArrayList<float[]>();

		for (var range : ranges) {
			var start = findSilenceClosestTo(range[0], silentGapsInAudio).start();
			var stop = findSilenceClosestTo(range[1], silentGapsInAudio).start();
			var e = new float[] { start, stop };
			if (Arrays.equals(range, ranges.getFirst()))
				e[0] = 0;
			if (Arrays.equals(range, ranges.getLast()))
				e[1] = totalDurationInMillis;
			betterRanges.add(e);
		}

		// 4. divide the file into N-mb chunks.
		var indx = 0;
		var listOfSegments = new ArrayList<TranscriptionSegment>();
		var numberFormat = numberFormat(); // not thread safe. not cheap.
		for (var r : betterRanges) {
			var destinationFile = new File(workspace, numberFormat.format(indx) + ".mp3");
			var start = (long) r[0];
			var stop = (long) r[1];
			bisect(originalAudio, destinationFile, start, stop);
			listOfSegments.add(new TranscriptionSegment(destinationFile, indx, start, stop));
			indx += 1;
		}

		return listOfSegments;
	}

	private NumberFormat numberFormat() {
		if (NUMBER_FORMAT.get() == null) {
			var formatter = NumberFormat.getInstance();
			formatter.setMinimumIntegerDigits(10);
			formatter.setGroupingUsed(false);
			NUMBER_FORMAT.set(formatter);
		}
		return NUMBER_FORMAT.get();
	}

	/**
	 * this used to parse the {@code Duration:} line out of the banner {@code ffmpeg -i}
	 * prints when you give it no output -- and it waited on the process before reading a
	 * word of it, which is the deadlock the {@link #drain} comment above describes. on a
	 * long episode the banner is big enough to fill the pipe, and a transcription that
	 * had not started yet would simply never start. ffprobe answers the question
	 * directly, and is drained before it is waited on.
	 */
	static Duration durationFor(File audio) throws Exception {
		var process = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
				audio.getAbsolutePath())
			.redirectErrorStream(true)
			.start();
		var output = drain(process);
		var exit = process.waitFor();
		Assert.state(exit == 0, () -> "ffprobe failed with exit code " + exit + ": " + output);
		return Duration.ofMillis(Math.round(Double.parseDouble(output.trim()) * 1000));
	}

	private SilenceDetector.Silence findSilenceClosestTo(float startToFind, SilenceDetector.Silence[] detectedSilence) {
		Assert.state(detectedSilence != null && detectedSilence.length > 0,
				"detectedSilence array cannot be null or empty");
		var closestSilence = detectedSilence[0];
		var closestDistance = Math.abs(closestSilence.start() - startToFind);
		for (var silence : detectedSilence) {
			var currentDistance = Math.abs(silence.start() - startToFind);
			if (currentDistance < closestDistance) {
				closestSilence = silence;
				closestDistance = currentDistance;
			}
		}
		return closestSilence;
	}

}
