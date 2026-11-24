package com.acme.support.shared;

/** Failure talking to an upstream system (Chatwoot, R2R). Messages never contain payloads or secrets. */
public class UpstreamException extends RuntimeException {

    private final String upstream;
    private final int status;
    private final boolean transientFailure;

    public UpstreamException(String upstream, int status, boolean transientFailure, String message, Throwable cause) {
        super(message, cause);
        this.upstream = upstream;
        this.status = status;
        this.transientFailure = transientFailure;
    }

    public String upstream() {
        return upstream;
    }

    /** HTTP status, or 0 for transport errors. */
    public int status() {
        return status;
    }

    public boolean isTransient() {
        return transientFailure;
    }
}
