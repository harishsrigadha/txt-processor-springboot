package com.example.txn.store;

import java.math.BigDecimal;

/** entryId equals the business transactionId, so a second post of the same transaction is impossible. */
public record LedgerEntry(String entryId, String accountId, String currency, BigDecimal signedAmount) { }
