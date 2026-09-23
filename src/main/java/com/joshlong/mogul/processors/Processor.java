package com.joshlong.mogul.processors;

public interface Processor {
    void process(ProcessorRequest request) throws Exception;
}
