package com.joshlong.mogul.processors.media;

import com.joshlong.mogul.processors.Processor;
import com.joshlong.mogul.processors.ProcessorRequest;
import com.joshlong.mogul.processors.Storage;
import com.joshlong.mogul.utils.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

/**
 * normalizes one uploaded file into the one format the rest of the system is willing to
 * deal with: images become a jpg under a megabyte, audio becomes a 192kbps mp3. this is
 * the {@code ffmpeg} and {@code magick} work that used to run inside the api, where a
 * handful of concurrent uploads could monopolize the machine that was also trying to
 * serve GraphQL requests.
 * <p>
 * everything it needs arrives in the {@link ProcessorRequest#context() context}: the
 * bucket and key to read, the bucket and key to write. it knows nothing about
 * {@code ManagedFile}s, moguls, or podcast episodes -- the api holds all of that and
 * reassembles it when the reply comes back bearing the correlation id.
 */
@Component(MediaNormalizationProcessor.PROCESSOR_ID)
class MediaNormalizationProcessor implements Processor {

	/**
	 * also the bean name, which is what the api addresses its requests to.
	 */
	public static final String PROCESSOR_ID = "mediaNormalizationProcessor";

	private static final String IMAGE = "image/";

	private static final String JPEG = "image/jpeg";

	private static final String MPEG = "audio/mpeg";

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final Storage storage;

	private final ImageEncoder imageEncoder;

	private final AudioEncoder audioEncoder;

	MediaNormalizationProcessor(Storage storage, ImageEncoder imageEncoder, AudioEncoder audioEncoder) {
		this.storage = storage;
		this.imageEncoder = imageEncoder;
		this.audioEncoder = audioEncoder;
	}

	@Override
	public Map<String, Object> process(ProcessorRequest request) throws Exception {
		var context = request.context();
		var inputBucket = this.required(context, MediaNormalization.INPUT_BUCKET);
		var inputKey = this.required(context, MediaNormalization.INPUT_KEY);
		var outputBucket = this.required(context, MediaNormalization.OUTPUT_BUCKET);
		var outputKey = this.required(context, MediaNormalization.OUTPUT_KEY);
		var inputContentType = this.required(context, MediaNormalization.INPUT_CONTENT_TYPE);
		var inputFilename = (String) context.get(MediaNormalization.INPUT_FILENAME);

		// the content type is the only thing that decides which tool runs. anything that
		// isn't an image is treated as audio, which is what the api did before this
		// moved out of process.
		var image = inputContentType.toLowerCase(Locale.ROOT).startsWith(IMAGE);
		var encoder = (Encoder<? extends EncodedFile>) (image ? this.imageEncoder : this.audioEncoder);
		var outputContentType = image ? JPEG : MPEG;

		// ffmpeg and magick both decide what they're looking at partly from the
		// extension, so the local copy keeps the name the mogul uploaded under.
		var local = FileUtils.tempFile("normalization-" + request.correlationId(), extensionFor(inputFilename));
		var ephemera = new HashSet<File>();
		ephemera.add(local);
		try {
			this.storage.download(inputBucket, inputKey, local);
			this.log.info("normalizing [{}/{}] ({}) into [{}/{}] ({})", inputBucket, inputKey, inputContentType,
					outputBucket, outputKey, outputContentType);
			var encoded = encoder.encode(local);
			ephemera.add(encoded.file());
			this.storage.upload(outputBucket, outputKey, encoded.file(), outputContentType);
			var response = new HashMap<String, Object>(encoded.context());
			response.put(MediaNormalization.OUTPUT_CONTENT_TYPE, outputContentType);
			response.put(MediaNormalization.OUTPUT_SIZE, encoded.file().length());
			return response;
		} //
		finally {
			for (var f : ephemera)
				if (FileUtils.delete(f))
					this.log.debug("deleted [{}] after media normalization", f.getAbsolutePath());
		}
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
