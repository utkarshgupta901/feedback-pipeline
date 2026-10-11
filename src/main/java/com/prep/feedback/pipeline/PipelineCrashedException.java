package com.prep.feedback.pipeline;

public class PipelineCrashedException extends RuntimeException {
    public PipelineCrashedException(Throwable cause) { super(cause); }
}
