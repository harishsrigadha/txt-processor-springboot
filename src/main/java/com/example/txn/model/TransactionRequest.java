package com.example.txn.model;

import java.math.BigDecimal;

/** Raw inbound request. Fields are nullable on purpose: validation happens in the service, not in the parser. */
public record TransactionRequest(
        String transactionId,
        String requestId,
        String accountId,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        Long sequenceNumber,
        String originalTransactionId) {

    /** Same business content, ignoring the retry-specific requestId. */
    public boolean sameBusinessPayload(TransactionRequest o) {
        return o != null
                && java.util.Objects.equals(transactionId, o.transactionId)
                && java.util.Objects.equals(accountId, o.accountId)
                && transactionType == o.transactionType
                && (amount == null ? o.amount == null : o.amount != null && amount.compareTo(o.amount) == 0)
                && java.util.Objects.equals(currency, o.currency)
                && java.util.Objects.equals(sequenceNumber, o.sequenceNumber)
                && java.util.Objects.equals(originalTransactionId, o.originalTransactionId);
    }
}
