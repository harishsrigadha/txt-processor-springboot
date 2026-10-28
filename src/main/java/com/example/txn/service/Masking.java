package com.example.txn.service;

/** Keeps customer identifiers out of logs: only the last 4 characters survive. */
public final class Masking {
    private Masking() { }

    public static String account(String accountId) {
        if (accountId == null) return "n/a";
        int n = accountId.length();
        return n <= 4 ? "****" : "****" + accountId.substring(n - 4);
    }
}
