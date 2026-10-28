package com.example.txn.validation;

import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Pure validation. Messages name fields only and never echo input values. */
public final class RequestValidator {
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private RequestValidator() { }

    public static List<String> validate(TransactionRequest r) {
        List<String> errors = new ArrayList<>();
        if (r == null) {
            errors.add("request is null");
            return errors;
        }
        if (blank(r.transactionId())) errors.add("transactionId is required");
        if (blank(r.requestId())) errors.add("requestId is required");
        if (blank(r.accountId())) errors.add("accountId is required");
        if (r.transactionType() == null) errors.add("transactionType is missing or unknown");
        if (r.amount() == null) {
            errors.add("amount is missing or not a number");
        } else if (r.amount().signum() <= 0) {
            errors.add("amount must be positive");
        } else if (r.amount().stripTrailingZeros().scale() > 2) {
            errors.add("amount supports at most 2 decimal places");
        }
        if (r.currency() == null || !CURRENCY.matcher(r.currency()).matches()) {
            errors.add("currency must be a 3-letter uppercase code");
        }
        if (r.sequenceNumber() == null || r.sequenceNumber() < 1) {
            errors.add("sequenceNumber must be a positive integer");
        }
        if (r.transactionType() == TransactionType.REVERSAL && blank(r.originalTransactionId())) {
            errors.add("originalTransactionId is required for REVERSAL");
        }
        return errors;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
