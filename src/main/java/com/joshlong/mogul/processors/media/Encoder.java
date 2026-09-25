package com.joshlong.mogul.processors.media;

import java.io.File;

interface Encoder<T extends EncodedFile> {

	T encode(File input);

}
