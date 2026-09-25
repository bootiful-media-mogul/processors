package com.joshlong.mogul.processors;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * the client keeps no record of what it asked for, so the reply has to be the whole
 * story: everything that went out, plus whatever the processor made of it, plus whether
 * it worked. keys the processor never looked at are the client's own return address, and
 * dropping one strands a piece of work that nothing else can identify.
 */
class ProcessorRequestHandlerTest {

	private final AtomicReference<ProcessorResponse> reply = new AtomicReference<>();

	private ProcessorRequestHandler handlerFor(String id, Processor processor) {
		return new ProcessorRequestHandler(Map.of(id, processor), this.reply::set);
	}

	private static ProcessorRequest request() {
		var context = new HashMap<String, Object>();
		context.put("inputBucket", "mogul-managedfiles-dev");
		context.put("inputKey", "f29b413c/39336145");
		// the client's return address. the processor has no idea these are here.
		context.put("outputManagedFileId", 7L);
		context.put("podcastEpisodeSegmentId", 2L);
		return new ProcessorRequest("aProcessor", "a-correlation-id", context);
	}

	@Test
	void theRequestComesHomeWithWhateverTheProcessorProduced() {
		this.handlerFor("aProcessor", _ -> Map.of("durationInMilliseconds", 90797L)).run(request());

		var response = this.reply.get();
		assertThat(response.success()).isTrue();
		assertThat(response.error()).isNull();
		assertThat(response.processorId()).isEqualTo("aProcessor");
		assertThat(response.correlationId()).isEqualTo("a-correlation-id");
		assertThat(response.context()).containsEntry("inputBucket", "mogul-managedfiles-dev")
			.containsEntry("inputKey", "f29b413c/39336145")
			.containsEntry("outputManagedFileId", 7L)
			.containsEntry("podcastEpisodeSegmentId", 2L)
			.containsEntry("durationInMilliseconds", 90797L);
	}

	@Test
	void aFailureStillCarriesTheReturnAddressAndSaysWhy() {
		this.handlerFor("aProcessor", _ -> {
			throw new IllegalStateException("ffmpeg exited with 1");
		}).run(request());

		var response = this.reply.get();
		assertThat(response.success()).isFalse();
		assertThat(response.error()).isEqualTo("ffmpeg exited with 1");
		// without this the client can't tell which of its outstanding jobs just failed.
		assertThat(response.context()).containsEntry("outputManagedFileId", 7L)
			.containsEntry("podcastEpisodeSegmentId", 2L);
	}

	@Test
	void aProcessorThatProducesNothingIsStillASuccess() {
		this.handlerFor("aProcessor", _ -> null).run(request());

		assertThat(this.reply.get().success()).isTrue();
		assertThat(this.reply.get().context()).containsEntry("outputManagedFileId", 7L);
	}

	@Test
	void anUnknownProcessorIsNotQuietlySwallowed() {
		var handler = this.handlerFor("aProcessor", _ -> Map.of());

		// this one is a deployment mistake, not a job failure: replying "it didn't work"
		// would tell the client to stop waiting on work no one has even tried to do.
		assertThat(this.reply.get()).isNull();
		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				() -> handler.run(new ProcessorRequest("noSuchProcessor", "c", Map.of())));
		assertThat(this.reply.get()).isNull();
	}

}
