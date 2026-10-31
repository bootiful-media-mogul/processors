package com.joshlong.mogul.processors.media;

import com.joshlong.mogul.processors.ProcessorRequest;
import com.joshlong.mogul.storage.Storage;
import com.joshlong.mogul.utils.ProcessUtils;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CleanedAudioIngestionProcessorTest {

	@Test
	void readsWhatCleanvoiceMuted() throws Exception {
		// the labels from a real muted cleanvoice run on two minutes of interview
		var labels = new String(new ClassPathResource("/cleanvoice/muted-labels.txt").getInputStream().readAllBytes());
		var detections = CleanedAudioIngestionProcessor.parseLabels(labels);
		assertThat(detections).hasSize(53);
		assertThat(detections.get(2))
			.isEqualTo(new CleanedAudioIngestionProcessor.Detection(4380, 4670, "FILLER_SOUND"));
		assertThat(detections).extracting(CleanedAudioIngestionProcessor.Detection::kind)
			.containsOnly("DEADAIR", "FILLER_SOUND", "STUTTERING");
	}

	@Test
	void writesTheDetectionsAsJson() {
		var json = CleanedAudioIngestionProcessor.json(
				java.util.List.of(new CleanedAudioIngestionProcessor.Detection(4380, 4670, "FILLER_SOUND")),
				java.util.List.of(new CleanedAudioIngestionProcessor.Detection(10000, 15000, null)));
		assertThat(json).isEqualTo(
				"{\"cleanvoice\":[{\"start\":4380,\"end\":4670,\"kind\":\"FILLER_SOUND\"}],\"silences\":[{\"start\":10000,\"end\":15000}]}");
	}

	@Test
	void fetchesTheCleanedAudioAndFindsItsSilences() throws Exception {
		assumeTrue(ffmpegIsInstalled(), "ffmpeg is not installed");
		// stands in for cleanvoice's storage: the audio, then the labels
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/clean.flac", exchange -> {
			var bytes = new ClassPathResource("/samples/sample-segment-0.mp3").getInputStream().readAllBytes();
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.createContext("/labels.txt", exchange -> {
			var bytes = "0.500\t0.900\tMUTE_FILLER_SOUND\n".getBytes();
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		var written = Files.createTempFile("cleaned", ".flac").toFile();
		written.deleteOnExit();
		var storage = new Storage(null) {
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
		try {
			var base = "http://127.0.0.1:" + server.getAddress().getPort();
			var context = new HashMap<String, Object>();
			context.put(CleanedAudioIngestion.AUDIO_URL, base + "/clean.flac?signature=secret");
			context.put(CleanedAudioIngestion.LABELS_URL, base + "/labels.txt?signature=secret");
			context.put(CleanedAudioIngestion.OUTPUT_BUCKET, "a-bucket");
			context.put(CleanedAudioIngestion.OUTPUT_KEY, "a-folder/cleaned");
			var correlationId = UUID.randomUUID().toString();
			var response = new CleanedAudioIngestionProcessor(storage, new AudioEncoder())
				.process(new ProcessorRequest(CleanedAudioIngestionProcessor.PROCESSOR_ID, correlationId, context));
			assertThat(written.length()).isPositive();
			assertThat((Long) response.get(CleanedAudioIngestion.DURATION_IN_MILLISECONDS)).isBetween(2200L, 2400L);
			assertThat((String) response.get(CleanedAudioIngestion.DETECTIONS))
				.startsWith("{\"cleanvoice\":[{\"start\":500,\"end\":900,\"kind\":\"FILLER_SOUND\"}],\"silences\":[");
			assertThat(response).containsEntry(CleanedAudioIngestion.OUTPUT_CONTENT_TYPE, "audio/flac");
			assertThat(new File(System.getProperty("java.io.tmpdir"), "cleaned-" + correlationId)).doesNotExist();
		} //
		finally {
			server.stop(0);
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

}
