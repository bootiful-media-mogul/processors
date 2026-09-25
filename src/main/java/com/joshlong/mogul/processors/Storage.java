package com.joshlong.mogul.processors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;

/**
 * S3, reduced to the two things a {@link Processor} ever wants: get the bytes onto local
 * disk, and put the bytes back when you're done with them.
 */
public class Storage {

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final S3Client s3;

	Storage(S3Client s3) {
		this.s3 = s3;
	}

	public void download(String bucket, String key, File destination) {
		// the SDK refuses to write over an existing file, and a temp file is created
		// the moment it is named, so clear the placeholder out of the way first.
		if (destination.exists())
			Assert.state(destination.delete(), () -> "could not clear the local file [" + destination + "]");
		this.log.debug("downloading [{}/{}] to [{}]", bucket, key, destination.getAbsolutePath());
		var request = GetObjectRequest.builder().bucket(bucket).key(key).build();
		this.s3.getObject(request, ResponseTransformer.toFile(destination));
		Assert.state(destination.exists(), () -> "the download of [" + bucket + "/" + key + "] produced no file");
	}

	public void upload(String bucket, String key, File file, String contentType) {
		Assert.state(file.exists() && file.isFile(), () -> "the file [" + file + "] must exist to be uploaded");
		this.log.debug("uploading [{}] ({} bytes) to [{}/{}]", file.getAbsolutePath(), file.length(), bucket, key);
		var builder = PutObjectRequest.builder().bucket(bucket).key(key);
		if (StringUtils.hasText(contentType))
			builder = builder.contentType(contentType);
		this.s3.putObject(builder.build(), RequestBody.fromFile(file));
	}

}
