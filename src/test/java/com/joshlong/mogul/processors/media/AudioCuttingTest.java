package com.joshlong.mogul.processors.media;

import com.joshlong.mogul.utils.ProcessUtils;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * an edited segment is rendered by keeping some ranges of its source and dropping the
 * rest. this runs the real {@code ffmpeg}, and is skipped where it isn't installed.
 */
class AudioCuttingTest {

	private static boolean ffmpegIsInstalled() {
		try {
			return ProcessUtils.runCommand("ffmpeg", "-version") == 0;
		} //
		catch (Exception e) {
			return false;
		}
	}

	private static File sample() throws Exception {
		var dir = Files.createTempDirectory("audio-cutting").toFile();
		dir.deleteOnExit();
		var file = new File(dir, "input.mp3");
		try (var in = new ClassPathResource("/samples/sample-segment-0.mp3").getInputStream()) {
			Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		return file;
	}

	@Test
	void keepsOnlyTheKeptRanges() throws Exception {
		assumeTrue(ffmpegIsInstalled(), "ffmpeg is not installed");
		var encoder = new AudioEncoder();
		var whole = encoder.encode(sample());
		var cut = encoder.encode(sample(), List.of(new KeptRange(0, 500), new KeptRange(1500, 2300)));
		// 500ms + 800ms. mp3 frames are ~24ms at 48kHz, and the encoder pads, so allow a
		// few frames either way; what matters is that the dropped second is gone.
		assertThat(whole.millisecondsDuration()).isGreaterThan(2200);
		assertThat(cut.millisecondsDuration()).isBetween(1250L, 1400L);
	}

	@Test
	void noKeptRangesKeepsEverything() throws Exception {
		assumeTrue(ffmpegIsInstalled(), "ffmpeg is not installed");
		var encoder = new AudioEncoder();
		assertThat(encoder.encode(sample(), List.of()).millisecondsDuration())
			.isEqualTo(encoder.encode(sample()).millisecondsDuration());
	}

	@Test
	void parsesTheWireFormat() {
		assertThat(KeptRange.parse("0-30000, 60000-234960")).containsExactly(new KeptRange(0, 30000),
				new KeptRange(60000, 234960));
		assertThat(KeptRange.parse(null)).isEmpty();
		assertThat(KeptRange.parse("")).isEmpty();
		assertThatIllegalStateException().isThrownBy(() -> KeptRange.parse("500-100"));
		assertThatIllegalStateException().isThrownBy(() -> KeptRange.parse("100"));
	}

	@Test
	void theFilterGraphJoinsEveryRangeInOrder() {
		var graph = AudioEncoder.cutFilter(List.of(new KeptRange(0, 500), new KeptRange(1500, 2300)));
		assertThat(graph).contains("atrim=start=0.000:end=0.500")
			.contains("atrim=start=1.500:end=2.300")
			.endsWith("[k0][k1]concat=n=2:v=0:a=1[kept]");
	}

}
