package com.joshlong.mogul.processors.media;

import com.joshlong.mogul.utils.FileUtils;
import com.joshlong.mogul.utils.ProcessUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * turns whatever the mogul uploaded into the one audio format everything downstream
 * assumes: a 48kHz stereo 192kbps mp3. given {@link KeptRange kept ranges}, it keeps only
 * those parts of the input, back to back, which is how an edited segment gets rendered.
 */
@Component
class AudioEncoder implements Encoder<AudioEncodedFile> {

	private final Logger log = LoggerFactory.getLogger(getClass());

	/**
	 * each kept piece fades in and out over this long, so a cut doesn't land mid-waveform
	 * and click. five milliseconds is too short to hear as a fade.
	 */
	private static final double CUT_FADE_SECONDS = 0.005;

	@Override
	public AudioEncodedFile encode(File input) {
		return this.encode(input, List.of());
	}

	AudioEncodedFile encode(File input, List<KeptRange> kept) {
		try {
			var inputAbsolutePath = input.getAbsolutePath();
			Assert.state(input.exists() && input.isFile(),
					"the input ['" + inputAbsolutePath + "'] must be a valid, existing file");
			var mp3 = FileUtils.createRelativeTempFile(input, ".mp3");
			var mp3AbsolutePath = mp3.getAbsolutePath();
			this.log.debug("encoding [{}] to [{}]", inputAbsolutePath, mp3AbsolutePath);
			// this fixed #113
			var command = new ArrayList<>(List.of("ffmpeg", "-y", "-i", inputAbsolutePath));
			if (!kept.isEmpty())
				command.addAll(List.of("-filter_complex", cutFilter(kept), "-map", "[kept]"));
			command.addAll(List.of("-ar", "48000", "-ac", "2", "-c:a", "libmp3lame", "-b:a", "192k", mp3AbsolutePath));
			var exit = ProcessUtils.runCommand(command.toArray(String[]::new));
			Assert.state(exit == 0, () -> "the ffmpeg command failed with exit code " + exit);
			return new AudioEncodedFile(mp3, this.durationInMilliseconds(mp3AbsolutePath));
		} //
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * trims each kept range out of the input, fades its edges, and joins them in order:
	 * {@code [0:a]atrim=...,asetpts=...,afade=...[k0];...;[k0][k1]concat=n=2:v=0:a=1[kept]}.
	 */
	static String cutFilter(List<KeptRange> kept) {
		var graph = new StringBuilder();
		var labels = new StringBuilder();
		for (var i = 0; i < kept.size(); i++) {
			var range = kept.get(i);
			var length = range.durationMilliseconds() / 1000.0;
			var fade = Math.min(CUT_FADE_SECONDS, length / 4);
			graph.append(String.format(Locale.ROOT,
					"[0:a]atrim=start=%.3f:end=%.3f,asetpts=PTS-STARTPTS,afade=t=in:d=%.4f,afade=t=out:st=%.4f:d=%.4f[k%d];",
					range.startMilliseconds() / 1000.0, range.endMilliseconds() / 1000.0, fade, length - fade, fade,
					i));
			labels.append("[k").append(i).append(']');
		}
		return graph.append(labels).append("concat=n=").append(kept.size()).append(":v=0:a=1[kept]").toString();
	}

	// apparently this can fail. so we will wrap it in excessive exception handling: a
	// missing duration is a cosmetic loss, not a reason to fail the whole normalization.
	long durationInMilliseconds(String file) {
		try {
			var pb = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
					file)
				.redirectErrorStream(true)
				.start();
			try (var i = new BufferedReader(new InputStreamReader(pb.getInputStream())); var o = new StringWriter()) {
				// drain before waiting: ffprobe will block forever writing into a pipe
				// nobody is reading if the output outgrows the buffer
				FileCopyUtils.copy(i, o);
				Assert.state(pb.waitFor() == 0, "ffprobe failed");
				return Math.round(Float.parseFloat(o.toString().trim()) * 1000);
			}
		} //
		catch (Exception e) {
			this.log.warn("couldn't compute duration for [{}]", file, e);
		}
		return 0;
	}

}
