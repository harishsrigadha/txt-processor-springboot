package com.example.txn.store;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Ledger entry and balance change happen together under one lock (atomic), and are guarded by a unique entryId. */
@Component
public final class Ledger {
    private final Map<String, LedgerEntry> entries = new LinkedHashMap<>();
    private final Map<String, BigDecimal> balances = new HashMap<>();

    public synchronized BigDecimal balance(String accountId, String currency) {
        return balances.getOrDefault(key(accountId, currency), BigDecimal.ZERO.setScale(2));
    }

    /** @return true if posted, false if an entry with this id already exists (no effect). */
    public synchronized boolean post(LedgerEntry entry) {
        if (entries.containsKey(entry.entryId())) {
            return false;
        }
        entries.put(entry.entryId(), entry);
        balances.merge(key(entry.accountId(), entry.currency()), entry.signedAmount(), BigDecimal::add);
        return true;
    }

    public synchronized Optional<LedgerEntry> find(String entryId) {
        return Optional.ofNullable(entries.get(entryId));
    }

    public synchronized List<LedgerEntry> entries() {
        return new ArrayList<>(entries.values());
    }

    /** Keys are "accountId|currency". */
    public synchronized Map<String, BigDecimal> balancesSnapshot() {
        return new HashMap<>(balances);
    }

    private static String key(String accountId, String currency) {
        return accountId + "|" + currency;
    }
}
