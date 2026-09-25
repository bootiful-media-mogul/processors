package com.joshlong.mogul.processors.media;

import com.joshlong.mogul.utils.FileUtils;
import com.joshlong.mogul.utils.ProcessUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.unit.DataSize;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;

/**
 * turns whatever the mogul uploaded into a jpg small enough to sit in a podcast feed,
 * walking the quality down until it fits under {@link #MAX_SIZE}.
 */
@Component
class ImageEncoder implements Encoder<ImageEncodedFile> {

	static final DataSize MAX_SIZE = DataSize.ofMegabytes(1);

	private final Logger log = LoggerFactory.getLogger(getClass());

	@Override
	public ImageEncodedFile encode(File input) {
		try {
			var output = this.isValidImage(input) ? Files
				.copy(input.toPath(), new File(input.getParentFile(), "copy-" + UUID.randomUUID() + ".jpg").toPath())
				.toFile() : this.scale(this.convertFileToJpeg(input));
			Assert.state(this.isValidSize(output),
					() -> "the output image [" + output.getAbsolutePath() + "] must be of the right file size");
			this.log.debug("encoded [{}] to [{}]", input.getAbsolutePath(), output.getAbsolutePath());
			return new ImageEncodedFile(output);
		} //
		catch (Throwable throwable) {
			throw new RuntimeException(throwable);
		}
	}

	private boolean isValidSize(File in) {
		return in.length() <= MAX_SIZE.toBytes();
	}

	private File convertFileToJpeg(File in) throws Exception {
		if (this.isValidType(in))
			return in;
		var converted = FileUtils.createRelativeTempFile(in, ".jpg");
		var exit = ProcessUtils.runCommand("magick", "convert", in.getAbsolutePath(), converted.getAbsolutePath());
		Assert.state(exit == 0, () -> "the magick convert command failed with exit code " + exit);
		return converted;
	}

	private boolean isValidType(File in) {
		return in.getName().toLowerCase(Locale.ROOT).endsWith(".jpg");
	}

	private File scale(File file) throws Exception {
		var original = file.getAbsolutePath();
		var dest = FileUtils.createRelativeTempFile(file);
		var output = dest.getAbsolutePath();
		var quality = 100;
		var size = 0L;
		do {
			var exit = ProcessUtils.runCommand("magick", "convert", original, "-quality", String.valueOf(quality),
					output);
			Assert.state(exit == 0, "the magick convert command failed to run.");
			size = Files.size(dest.toPath());
			quality -= 5;
		} //
		while (size > MAX_SIZE.toBytes() && quality > 0);
		return dest;
	}

	private boolean isValidImage(File f) {
		return this.isValidSize(f) && this.isValidType(f);
	}

}
