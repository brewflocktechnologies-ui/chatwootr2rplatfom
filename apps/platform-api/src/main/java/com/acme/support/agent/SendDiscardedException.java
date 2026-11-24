package com.acme.support.agent;

/** Ownership or Chatwoot eligibility changed before the send; the late AI result is discarded. */
public class SendDiscardedException extends RuntimeException {

    public SendDiscardedException(String message) {
        super(message);
    }
}
