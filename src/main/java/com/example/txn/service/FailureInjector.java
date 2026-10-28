package com.example.txn.service;

import com.example.txn.model.TransactionRecord;

/** Hook that runs just before the atomic commit, simulating a downstream call that can fail. */
@FunctionalInterface
public interface FailureInjector {
    FailureInjector NONE = (record, attempt) -> { };

    void beforeCommit(TransactionRecord record, int attempt) throws TransientFailureException;
}
