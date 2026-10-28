package com.example.txn.model;

/** Lifecycle: RECEIVED -> (PENDING_SEQUENCE) -> PROCESSING -> (RETRY_PENDING -> PROCESSING)* -> PROCESSED | FAILED.
 *  DUPLICATE and REJECTED are terminal dispositions of an inbound request that never became a transaction. */
public enum TransactionStatus {
    RECEIVED, PENDING_SEQUENCE, PROCESSING, RETRY_PENDING, PROCESSED, FAILED, DUPLICATE, REJECTED
}
