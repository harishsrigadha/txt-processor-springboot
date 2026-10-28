package com.example.txn.store;

/** Deduplication store. Swap the in-memory version for a DB table with unique constraints in production. */
public interface IdempotencyStore {
    boolean isDuplicate(String transactionId, String requestId);

    void remember(String transactionId, String requestId);
}
