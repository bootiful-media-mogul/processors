package com.joshlong.mogul.processors.transcripts;

import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

import java.time.Duration;

@Configuration
class TranscriptsConfiguration {

	@Bean
	ChunkingTranscriber chunkingTranscriber(OpenAiAudioTranscriptionModel transcriptModel,
			@Value("${mogul.transcripts.max-chunk-size-in-bytes:10485760}") long maxChunkSizeInBytes) {
		var retryTemplate = new RetryTemplate(RetryPolicy.builder().timeout(Duration.ofMinutes(2)).build());
		return new ChunkingTranscriber(transcriptModel, retryTemplate, maxChunkSizeInBytes);
	}

}
