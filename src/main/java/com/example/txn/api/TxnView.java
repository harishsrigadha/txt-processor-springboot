package com.example.txn.api;

import com.example.txn.model.FailureKind;
import com.example.txn.model.TransactionStatus;
import java.util.List;

/** Read model for GET /api/transactions/{id}. */
public record TxnView(String transactionId, TransactionStatus status, int attempts,
                      FailureKind failureKind, String failureReason, List<String> history) { }
