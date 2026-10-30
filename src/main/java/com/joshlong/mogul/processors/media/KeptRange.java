package com.joshlong.mogul.processors.media;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * one stretch of a source file to keep, in milliseconds from its start. an edited segment
 * is the list of these, played back to back.
 */
record KeptRange(long startMilliseconds, long endMilliseconds) {

	KeptRange {
		Assert.state(startMilliseconds >= 0, "a kept range can't start before the file does");
		Assert.state(endMilliseconds > startMilliseconds, "a kept range must end after it starts");
	}

	long durationMilliseconds() {
		return this.endMilliseconds - this.startMilliseconds;
	}

	/**
	 * parses the wire format, {@code 0-30000,60000-234960}. absent or empty means there
	 * are no edits, which is an empty list rather than a null.
	 */
	static List<KeptRange> parse(String ranges) {
		var kept = new ArrayList<KeptRange>();
		if (!StringUtils.hasText(ranges))
			return kept;
		for (var range : ranges.split(",")) {
			var bounds = range.trim().split("-");
			Assert.state(bounds.length == 2, () -> "the kept range [" + range + "] must look like start-end");
			kept.add(new KeptRange(Long.parseLong(bounds[0].trim()), Long.parseLong(bounds[1].trim())));
		}
		return kept;
	}

}
