package com.example.txn.store;

import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Set;

@Component
public final class InMemoryIdempotencyStore implements IdempotencyStore {
    private final Set<String> transactionIds = new HashSet<>();
    private final Set<String> requestIds = new HashSet<>();

    @Override
    public synchronized boolean isDuplicate(String transactionId, String requestId) {
        return transactionIds.contains(transactionId) || requestIds.contains(requestId);
    }

    @Override
    public synchronized void remember(String transactionId, String requestId) {
        transactionIds.add(transactionId);
        requestIds.add(requestId);
    }
}
