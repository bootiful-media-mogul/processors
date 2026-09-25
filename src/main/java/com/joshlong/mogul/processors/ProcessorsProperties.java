package com.joshlong.mogul.processors;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * a processor is handed the coordinates of the S3 objects it should read and write, so
 * the only thing this node needs to be configured with is how to talk to S3 at all. the
 * rich domain -- who owns the file, what it is a part of, what should happen next --
 * stays in the api.
 */
@ConfigurationProperties(prefix = "mogul")
public record ProcessorsProperties(Aws aws) {

	public record Aws(String accessKey, String accessKeySecret, String region) {
	}

}
