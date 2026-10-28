package com.example.txn.service;

import com.example.txn.model.TransactionRecord;
import java.util.HashMap;
import java.util.Map;

/** Deterministic failure simulation: "fail transaction X the next N times". */
public final class ScriptedFailureInjector implements FailureInjector {
    private final Map<String, Integer> remaining = new HashMap<>();

    public ScriptedFailureInjector failTimes(String transactionId, int times) {
        remaining.put(transactionId, times);
        return this;
    }

    public ScriptedFailureInjector failAlways(String transactionId) {
        return failTimes(transactionId, Integer.MAX_VALUE);
    }

    public ScriptedFailureInjector heal(String transactionId) {
        remaining.remove(transactionId);
        return this;
    }

    @Override
    public void beforeCommit(TransactionRecord record, int attempt) {
        String id = record.request().transactionId();
        int left = remaining.getOrDefault(id, 0);
        if (left > 0) {
            remaining.put(id, left == Integer.MAX_VALUE ? left : left - 1);
            throw new TransientFailureException("simulated downstream timeout");
        }
    }
}
