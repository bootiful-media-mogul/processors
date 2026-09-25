package com.joshlong.mogul.processors;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

@ImportRuntimeHints(ProcessorsApplication.Hints.class)
@SpringBootApplication
public class ProcessorsApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProcessorsApplication.class, args);
	}

	static class Hints implements RuntimeHintsRegistrar {

		@Override
		public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
			for (var c : new Class<?>[] { ProcessorResponse.class, ProcessorRequest.class })
				hints.reflection().registerType(c, MemberCategory.values());
		}

	}

}
