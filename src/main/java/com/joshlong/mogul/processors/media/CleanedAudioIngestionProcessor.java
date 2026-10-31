package com.joshlong.mogul.processors.media;

import com.joshlong.mogul.processors.Processor;
import com.joshlong.mogul.processors.ProcessorRequest;
import com.joshlong.mogul.processors.transcripts.SilenceDetector;
import com.joshlong.mogul.storage.Storage;
import com.joshlong.mogul.utils.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * brings cleanvoice's result home. cleanvoice has already done the cleaning, on the
 * mogul's own account; what's left is fetching the cleaned audio before its link expires,
 * reading what it muted, and finding the long silences in it, which are much easier to
 * find now the background noise is gone.
 * <p>
 * the urls are cleanvoice's, signed and short-lived, so no credentials come through here.
 */
@Component(CleanedAudioIngestionProcessor.PROCESSOR_ID)
class CleanedAudioIngestionProcessor implements Processor {

	public static final String PROCESSOR_ID = "cleanedAudioIngestionProcessor";

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final HttpClient http = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(30))
		.build();

	private final Storage storage;

	private final AudioEncoder audioEncoder;

	CleanedAudioIngestionProcessor(Storage storage, AudioEncoder audioEncoder) {
		this.storage = storage;
		this.audioEncoder = audioEncoder;
	}

	record Detection(long start, long end, String kind) {
	}

	@Override
	public Map<String, Object> process(ProcessorRequest request) throws Exception {
		var context = request.context();
		var audioUrl = this.required(context, CleanedAudioIngestion.AUDIO_URL);
		var labelsUrl = this.required(context, CleanedAudioIngestion.LABELS_URL);
		var outputBucket = this.required(context, CleanedAudioIngestion.OUTPUT_BUCKET);
		var outputKey = this.required(context, CleanedAudioIngestion.OUTPUT_KEY);
		var workspace = new File(System.getProperty("java.io.tmpdir"), "cleaned-" + request.correlationId());
		Assert.state(workspace.isDirectory() || workspace.mkdirs(),
				() -> "could not create the workspace [" + workspace + "]");
		try {
			var audio = new File(workspace, "cleaned.flac");
			var labels = new File(workspace, "labels.txt");
			this.download(audioUrl, audio);
			this.download(labelsUrl, labels);
			var cleanvoice = parseLabels(Files.readString(labels.toPath()));
			var silences = new ArrayList<Detection>();
			for (var silence : SilenceDetector.detect(audio))
				silences.add(new Detection(Math.round(silence.start()), Math.round(silence.end()), null));
			var duration = this.audioEncoder.durationInMilliseconds(audio.getAbsolutePath());
			this.storage.write(outputBucket, outputKey, audio,
					MediaType.parseMediaType(CleanedAudioIngestion.CONTENT_TYPE));
			this.log.info("ingested cleaned audio into [{}/{}]: {} cleanvoice edits, {} silences", outputBucket,
					outputKey, cleanvoice.size(), silences.size());
			var response = new HashMap<String, Object>();
			response.put(CleanedAudioIngestion.OUTPUT_CONTENT_TYPE, CleanedAudioIngestion.CONTENT_TYPE);
			response.put(CleanedAudioIngestion.DURATION_IN_MILLISECONDS, duration);
			response.put(CleanedAudioIngestion.DETECTIONS, json(cleanvoice, silences));
			return response;
		} //
		finally {
			if (!FileUtils.delete(workspace))
				this.log.warn("could not delete the workspace [{}]; this node will leak disk until it restarts",
						workspace.getAbsolutePath());
		}
	}

	private void download(String url, File destination) throws Exception {
		var response = this.http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30)).build(),
				HttpResponse.BodyHandlers.ofFile(destination.toPath()));
		// the query string is the signature; keep it out of the logs and the exception
		var where = url.replaceAll("[?].*", "");
		Assert.state(response.statusCode() / 100 == 2,
				() -> "couldn't download [" + where + "]: HTTP " + response.statusCode());
	}

	/**
	 * audacity labels: {@code start\tend\tkind} per line, in seconds, which is what
	 * cleanvoice writes for a muted edit.
	 */
	static List<Detection> parseLabels(String labels) {
		var detections = new ArrayList<Detection>();
		for (var line : labels.split("\\R")) {
			var columns = line.trim().split("\t");
			if (columns.length < 3)
				continue;
			var start = Math.round(Double.parseDouble(columns[0]) * 1000);
			var end = Math.round(Double.parseDouble(columns[1]) * 1000);
			if (end <= start)
				continue;
			var kind = columns[2].trim().toUpperCase(Locale.ROOT).replaceFirst("^MUTE_", "");
			detections.add(new Detection(start, end, kind));
		}
		return detections;
	}

	/** a fixed, flat shape: numbers, and kinds made of letters and underscores */
	static String json(List<Detection> cleanvoice, List<Detection> silences) {
		var out = new StringBuilder("{\"cleanvoice\":[");
		for (var i = 0; i < cleanvoice.size(); i++) {
			var d = cleanvoice.get(i);
			Assert.state(d.kind().matches("[A-Z_]+"), () -> "unexpected cleanvoice edit kind [" + d.kind() + "]");
			out.append(i == 0 ? "" : ",")
				.append("{\"start\":")
				.append(d.start())
				.append(",\"end\":")
				.append(d.end())
				.append(",\"kind\":\"")
				.append(d.kind())
				.append("\"}");
		}
		out.append("],\"silences\":[");
		for (var i = 0; i < silences.size(); i++) {
			var d = silences.get(i);
			out.append(i == 0 ? "" : ",")
				.append("{\"start\":")
				.append(d.start())
				.append(",\"end\":")
				.append(d.end())
				.append('}');
		}
		return out.append("]}").toString();
	}

	private String required(Map<String, Object> context, String key) {
		var value = context.get(key);
		Assert.state(value instanceof String s && StringUtils.hasText(s),
				() -> "the request context must carry a '" + key + "'");
		return (String) value;
	}

}
