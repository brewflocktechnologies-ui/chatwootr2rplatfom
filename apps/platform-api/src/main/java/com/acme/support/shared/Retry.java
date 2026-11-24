package com.acme.support.shared;

import java.time.Duration;
import java.util.function.Supplier;

/** Bounded exponential backoff for transient upstream failures only. */
public final class Retry {

    private Retry() {
    }

    public static <T> T run(int maxAttempts, Duration baseDelay, Supplier<T> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (UpstreamException e) {
                if (!e.isTransient() || attempt >= maxAttempts) {
                    throw e;
                }
                sleep(baseDelay.multipliedBy(1L << (attempt - 1)));
            }
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during retry backoff", e);
        }
    }
}
