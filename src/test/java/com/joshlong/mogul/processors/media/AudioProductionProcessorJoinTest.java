package com.joshlong.mogul.processors.media;

import com.joshlong.mogul.processors.ProcessorRequest;
import com.joshlong.mogul.storage.Storage;
import com.joshlong.mogul.utils.ProcessUtils;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.util.FileCopyUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * the real thing, against real {@code ffmpeg}: two segments in, one mp3 out, longer than
 * either of them. an episode that is silently only its first segment is the failure this
 * guards against, and it is not one the exit code of the concat would report.
 * <p>
 * skipped where ffmpeg isn't installed. the container this module ships in has it.
 */
class AudioProductionProcessorJoinTest {

	private static final String BUCKET = "a-bucket";

	/**
	 * stands in for S3: reads hand back the fixture, writes land on local disk where the
	 * test can look at them.
	 */
	private static class LocalStorage extends Storage {

		private final File written;

		LocalStorage(File written) {
			super(null);
			this.written = written;
		}

		@Override
		public void read(String bucket, String objectName, File destination) {
			try (var in = new ClassPathResource("/samples/sample-segment-0.mp3").getInputStream();
					var out = new FileOutputStream(destination)) {
				FileCopyUtils.copy(in, out);
			} //
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void write(String bucket, String objectName, File file, MediaType mediaType) {
			try {
				Files.copy(file.toPath(), this.written.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} //
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

	}

	private static boolean ffmpegIsInstalled() {
		try {
			return ProcessUtils.runCommand("ffmpeg", "-version") == 0;
		} //
		catch (Exception e) {
			return false;
		}
	}

	private record Produced(File mp3, long durationInMilliseconds, Map<String, Object> response) {
	}

	private Produced produce(int segments) throws Exception {
		var output = File.createTempFile("produced-" + UUID.randomUUID(), ".mp3");
		output.deleteOnExit();
		var processor = new AudioProductionProcessor(new LocalStorage(output), new AudioEncoder());
		var context = new HashMap<String, Object>();
		context.put(AudioProduction.INPUT_BUCKETS, Collections.nCopies(segments, BUCKET));
		var keys = new ArrayList<String>();
		for (var i = 0; i < segments; i++)
			keys.add("a-folder/segment-" + i);
		context.put(AudioProduction.INPUT_KEYS, keys);
		context.put(AudioProduction.OUTPUT_BUCKET, BUCKET);
		context.put(AudioProduction.OUTPUT_KEY, "a-folder/produced-audio.mp3");
		var correlationId = UUID.randomUUID().toString();
		var response = processor
			.process(new ProcessorRequest(AudioProductionProcessor.PROCESSOR_ID, correlationId, context));
		// the workspace is the tightest budget this workload has; a job that leaves one
		// behind fills the pod after a few dozen renders.
		assertThat(new File(System.getProperty("java.io.tmpdir"), "production-" + correlationId)).doesNotExist();
		return new Produced(output, (Long) response.get(AudioProduction.DURATION_IN_MILLISECONDS), response);
	}

	@Test
	void everySegmentEndsUpInTheProducedEpisode() throws Exception {
		assumeTrue(ffmpegIsInstalled(), "ffmpeg is not installed");

		var one = this.produce(1);
		var two = this.produce(2);

		assertThat(one.durationInMilliseconds()).isPositive();
		// the join is the whole job. two of the same segment has to be about twice as
		// long as one of them -- an output the length of a single segment means the
		// concat quietly kept only the first file.
		assertThat(two.durationInMilliseconds()).isGreaterThan((long) (one.durationInMilliseconds() * 1.8));

		assertThat(two.response()).containsEntry(AudioProduction.OUTPUT_CONTENT_TYPE, "audio/mpeg");
		assertThat(two.mp3()).isFile();
		assertThat((Long) two.response().get(AudioProduction.OUTPUT_SIZE)).isEqualTo(two.mp3().length());
	}

}
