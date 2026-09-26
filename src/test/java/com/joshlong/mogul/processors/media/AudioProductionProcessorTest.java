package com.joshlong.mogul.processors.media;

import com.joshlong.mogul.processors.ProcessorRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * a malformed request has to be rejected before anything is downloaded, because the
 * alternative is finding out half an hour and several gigabytes of ephemeral storage
 * later. these all fail on the contract alone, which is why the collaborators can be
 * null: nothing gets as far as touching them.
 */
class AudioProductionProcessorTest {

	private final AudioProductionProcessor processor = new AudioProductionProcessor(null, null);

	private static Map<String, Object> context() {
		var context = new HashMap<String, Object>();
		context.put(AudioProduction.INPUT_BUCKETS, List.of("mogul-managedfiles-dev", "mogul-managedfiles-dev"));
		context.put(AudioProduction.INPUT_KEYS, List.of("f29b413c/1", "f29b413c/2"));
		context.put(AudioProduction.OUTPUT_BUCKET, "mogul-managedfiles-dev");
		context.put(AudioProduction.OUTPUT_KEY, "f29b413c/produced-audio.mp3");
		// the api's own return address. the processor never looks at these.
		context.put("podcastEpisodeId", 42L);
		context.put("outputManagedFileId", 7L);
		return context;
	}

	private void process(Map<String, Object> context) throws Exception {
		this.processor
			.process(new ProcessorRequest(AudioProductionProcessor.PROCESSOR_ID, "a-correlation-id", context));
	}

	@Test
	void anEpisodeWithNoSegmentsIsNotAnEmptyRender() {
		var context = context();
		context.put(AudioProduction.INPUT_KEYS, List.of());
		assertThatIllegalStateException().isThrownBy(() -> this.process(context))
			.withMessageContaining(AudioProduction.INPUT_KEYS);
	}

	@Test
	void theTwoListsDescribeOneListAndMustAgree() {
		var context = context();
		context.put(AudioProduction.INPUT_BUCKETS, List.of("mogul-managedfiles-dev"));
		assertThatIllegalStateException().isThrownBy(() -> this.process(context)).withMessageContaining("must agree");
	}

	@Test
	void thereIsNowhereToPutTheResult() {
		var context = context();
		context.remove(AudioProduction.OUTPUT_KEY);
		assertThatIllegalStateException().isThrownBy(() -> this.process(context))
			.withMessageContaining(AudioProduction.OUTPUT_KEY);
	}

}
