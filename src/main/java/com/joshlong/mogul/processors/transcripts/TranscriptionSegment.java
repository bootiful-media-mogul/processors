package com.joshlong.mogul.processors.transcripts;

import java.io.File;

record TranscriptionSegment(File audio, int order, long startInMilliseconds, long stopInMilliseconds) {
}
