package com.joshlong.mogul.processors.media;

import com.joshlong.mogul.processors.ProcessorRequest;
import com.joshlong.mogul.storage.Storage;
import com.joshlong.mogul.utils.ProcessUtils;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.util.FileCopyUtils;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AudioWaveformProcessorTest {

	private record Waveform(String magic, int peaksPerSecond, long durationInMilliseconds, byte[] peaks) {
	}

	private static Waveform read(File file) throws Exception {
		try (var in = new DataInputStream(new FileInputStream(file))) {
			var magic = new String(in.readNBytes(4));
			var peaksPerSecond = in.readUnsignedShort();
			var duration = in.readInt() & 0xffffffffL;
			var count = in.readInt();
			var peaks = in.readNBytes(count);
			assertThat(in.read()).as("nothing after the peaks").isEqualTo(-1);
			return new Waveform(magic, peaksPerSecond, duration, peaks);
		}
	}

	@Test
	void scalesPeaksToTheLoudestMoment() throws Exception {
		// one second of silence, then one second at half volume, then a quiet blip
		var rate = 8000;
		var samples = ByteBuffer.allocate(rate * 2 * 2 + 400 * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (var i = 0; i < rate; i++)
			samples.putShort((short) 0);
		for (var i = 0; i < rate; i++)
			samples.putShort((short) (i % 2 == 0 ? 16000 : -16000));
		for (var i = 0; i < 400; i++)
			samples.putShort((short) 4000);
		var pcm = Files.createTempFile("waveform", ".pcm").toFile();
		var out = Files.createTempFile("waveform", ".bin").toFile();
		pcm.deleteOnExit();
		out.deleteOnExit();
		Files.write(pcm.toPath(), samples.array());

		var duration = AudioWaveformProcessor.write(pcm, out);

		var waveform = read(out);
		assertThat(duration).isEqualTo(2050);
		assertThat(waveform.magic()).isEqualTo("MWF1");
		assertThat(waveform.peaksPerSecond()).isEqualTo(20);
		assertThat(waveform.durationInMilliseconds()).isEqualTo(2050);
		assertThat(waveform.peaks()).hasSize(41);
		assertThat(waveform.peaks()[0]).isZero();
		assertThat(waveform.peaks()[19]).isZero();
		assertThat(waveform.peaks()[20] & 0xff).isEqualTo(255);
		assertThat(waveform.peaks()[40] & 0xff).isEqualTo(64);
	}

	@Test
	void drawsARealFileThroughStorage() throws Exception {
		assumeTrue(ffmpegIsInstalled(), "ffmpeg is not installed");
		var written = Files.createTempFile("waveform-written", ".bin").toFile();
		written.deleteOnExit();
		var storage = new Storage(null) {

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
					Files.copy(file.toPath(), written.toPath(), StandardCopyOption.REPLACE_EXISTING);
				} //
				catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		};
		var context = new HashMap<String, Object>();
		context.put(AudioWaveform.INPUT_BUCKET, "a-bucket");
		context.put(AudioWaveform.INPUT_KEY, "a-folder/segment");
		context.put(AudioWaveform.INPUT_FILENAME, "segment.mp3");
		context.put(AudioWaveform.OUTPUT_BUCKET, "a-bucket");
		context.put(AudioWaveform.OUTPUT_KEY, "a-folder/waveform");
		var correlationId = UUID.randomUUID().toString();

		var response = new AudioWaveformProcessor(storage)
			.process(new ProcessorRequest(AudioWaveformProcessor.PROCESSOR_ID, correlationId, context));

		var waveform = read(written);
		// the sample is 2.3 seconds long
		assertThat(waveform.durationInMilliseconds()).isBetween(2200L, 2400L);
		assertThat(waveform.peaks().length).isBetween(44, 48);
		assertThat(response).containsEntry(AudioWaveform.DURATION_IN_MILLISECONDS, waveform.durationInMilliseconds())
			.containsEntry(AudioWaveform.PEAKS_PER_SECOND, 20)
			.containsEntry(AudioWaveform.OUTPUT_SIZE, written.length())
			.containsEntry(AudioWaveform.OUTPUT_CONTENT_TYPE, "application/octet-stream");
		assertThat(new File(System.getProperty("java.io.tmpdir"), "waveform-" + correlationId)).doesNotExist();
	}

	private static boolean ffmpegIsInstalled() {
		try {
			return ProcessUtils.runCommand("ffmpeg", "-version") == 0;
		} //
		catch (Exception e) {
			return false;
		}
	}

}
