package com.example.txn.api;

import com.example.txn.model.TransactionStatus;

/** Result of submitting one request. */
public record TxnResponse(String transactionId, TransactionStatus status) { }
