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

/**
 * turns whatever the mogul uploaded into the one audio format everything downstream
 * assumes: a 48kHz stereo 192kbps mp3.
 */
@Component
class AudioEncoder implements Encoder<AudioEncodedFile> {

	private final Logger log = LoggerFactory.getLogger(getClass());

	@Override
	public AudioEncodedFile encode(File input) {
		try {
			var inputAbsolutePath = input.getAbsolutePath();
			Assert.state(input.exists() && input.isFile(),
					"the input ['" + inputAbsolutePath + "'] must be a valid, existing file");
			var mp3 = FileUtils.createRelativeTempFile(input, ".mp3");
			var mp3AbsolutePath = mp3.getAbsolutePath();
			this.log.debug("encoding [{}] to [{}]", inputAbsolutePath, mp3AbsolutePath);
			// this fixed #113
			var exit = ProcessUtils.runCommand("ffmpeg", "-y", "-i", inputAbsolutePath, "-ar", "48000", "-ac", "2",
					"-c:a", "libmp3lame", "-b:a", "192k", mp3AbsolutePath);
			Assert.state(exit == 0, () -> "the ffmpeg command failed with exit code " + exit);
			return new AudioEncodedFile(mp3, this.durationInMilliseconds(mp3AbsolutePath));
		} //
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	// apparently this can fail. so we will wrap it in excessive exception handling: a
	// missing duration is a cosmetic loss, not a reason to fail the whole normalization.
	private long durationInMilliseconds(String file) {
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
