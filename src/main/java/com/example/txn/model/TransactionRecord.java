package com.example.txn.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Mutable processing state of one unique business transaction. */
public final class TransactionRecord {
    private final TransactionRequest request;
    private TransactionStatus status = TransactionStatus.RECEIVED;
    private int attempts;
    private FailureKind failureKind;
    private String failureReason;
    private final List<String> history = new ArrayList<>();

    public TransactionRecord(TransactionRequest request) {
        this.request = request;
        history.add("RECEIVED");
    }

    public void transition(TransactionStatus next, String note) {
        status = next;
        history.add(note == null ? next.name() : next + "(" + note + ")");
    }

    public void beginAttempt() {
        attempts++;
        transition(TransactionStatus.PROCESSING, "attempt " + attempts);
    }

    public void fail(FailureKind kind, String reason) {
        failureKind = kind;
        failureReason = reason;
        transition(TransactionStatus.FAILED, reason);
    }

    public void resetForReplay() {
        attempts = 0;
        failureKind = null;
        failureReason = null;
        history.add("REPLAY_REQUESTED");
    }

    public TransactionRequest request() { return request; }
    public TransactionStatus status() { return status; }
    public int attempts() { return attempts; }
    public FailureKind failureKind() { return failureKind; }
    public String failureReason() { return failureReason; }
    public List<String> history() { return Collections.unmodifiableList(history); }
}
