package com.example.txn.model;

public enum FailureKind {
    /** Business rule violation (e.g. insufficient funds). Not retried. */
    BUSINESS,
    /** Transient errors persisted past the retry limit. Recoverable via replay. */
    TRANSIENT_EXHAUSTED
}
