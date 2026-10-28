package com.example.txn.service;

/** Retryable failure (timeout, downstream unavailable, lock contention...). */
public class TransientFailureException extends RuntimeException {
    public TransientFailureException(String message) { super(message); }
}
