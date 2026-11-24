package com.acme.support.agent;

/**
 * A customer-facing send may or may not have reached Chatwoot. It is never retried automatically
 * (fail closed) so the customer cannot receive two AI replies.
 */
public class SendOutcomeUnknownException extends RuntimeException {

    public SendOutcomeUnknownException(String message) {
        super(message);
    }
}
