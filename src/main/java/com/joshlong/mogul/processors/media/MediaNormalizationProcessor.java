package com.joshlong.mogul.processors.media;

import com.joshlong.mogul.processors.Processor;
import com.joshlong.mogul.processors.ProcessorRequest;
import org.springframework.stereotype.Component;

@Component
class MediaNormalizationProcessor
    implements Processor
{

    @Override
    public void process(ProcessorRequest request) throws Exception {
        IO.println("Processing media normalization request [" + request + "]");
    }
}
