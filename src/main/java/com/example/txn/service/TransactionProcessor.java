package com.example.txn.service;

import com.example.txn.model.FailureKind;
import com.example.txn.model.TransactionRecord;
import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionStatus;
import com.example.txn.model.TransactionType;
import com.example.txn.store.IdempotencyStore;
import com.example.txn.store.Ledger;
import com.example.txn.store.LedgerEntry;
import com.example.txn.validation.RequestValidator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Idempotent transaction processor.
 *
 * Ordering strategy (Option A, sequence-based): sequenceNumber is per account, starting at 1.
 * A request is executed only when every earlier sequence of that account has been consumed;
 * otherwise it is buffered as PENDING_SEQUENCE and released when the gap fills.
 *
 * Idempotency: transactionId and requestId are checked in the IdempotencyStore before anything else,
 * and the ledger uses transactionId as a unique entry id (second line of defence).
 *
 * Failure policy: transient errors are retried up to maxAttempts. A business failure or exhausted retry
 * marks the record FAILED and the account cursor moves on (no head-of-line blocking); failed
 * transient records can be recovered with {@link #replayFailed(String)}.
 *
 * Thread-safety: all public methods are synchronized (single-writer). For multiple workers, partition by accountId.
 */
@Service
public class TransactionProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(TransactionProcessor.class);

    private final IdempotencyStore idempotencyStore;
    private final Ledger ledger;
    private final FailureInjector failureInjector;
    private final int maxAttempts;
    private final long backoffMillis;

    private final Map<String, TransactionRecord> records = new LinkedHashMap<>();
    private final Map<String, AccountState> accounts = new HashMap<>();
    private final Set<String> reversed = new HashSet<>();
    private final List<String> rejections = new ArrayList<>();
    private int totalReceived;
    private int duplicates;
    private int payloadMismatches;

    private static final class AccountState {
        long nextExpected = 1;
        final TreeMap<Long, TransactionRecord> buffered = new TreeMap<>();
    }

    private static final class BusinessRuleException extends RuntimeException {
        BusinessRuleException(String code) { super(code); }
    }

    public TransactionProcessor(IdempotencyStore idempotencyStore, Ledger ledger,
                                FailureInjector failureInjector,
                                @Value("${txn.max-attempts:3}") int maxAttempts,
                                @Value("${txn.backoff-millis:0}") long backoffMillis) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        this.idempotencyStore = idempotencyStore;
        this.ledger = ledger;
        this.failureInjector = failureInjector;
        this.maxAttempts = maxAttempts;
        this.backoffMillis = backoffMillis;
    }

    /** Processes a batch; an invalid or failing record never stops the rest. */
    public synchronized void processAll(List<TransactionRequest> requests) {
        for (TransactionRequest r : requests) {
            submit(r);
        }
    }

    /** @return status of this request right after handling (REJECTED, DUPLICATE, or the record status). */
    public synchronized TransactionStatus submit(TransactionRequest r) {
        totalReceived++;

        List<String> errors = RequestValidator.validate(r);
        if (!errors.isEmpty()) {
            String id = r == null || r.transactionId() == null ? "n/a" : r.transactionId();
            rejections.add("txn=" + id + " reasons=" + errors);
            LOG.warn("event=REJECTED txn=" + id + " reasons=" + errors);
            return TransactionStatus.REJECTED;
        }

        if (idempotencyStore.isDuplicate(r.transactionId(), r.requestId())) {
            duplicates++;
            TransactionRecord original = records.get(r.transactionId());
            if (original != null && !original.request().sameBusinessPayload(r)) {
                payloadMismatches++;
                LOG.warn("event=DUPLICATE_PAYLOAD_MISMATCH txn=" + r.transactionId() + " action=ignored_first_write_wins");
            } else {
                LOG.info("event=DUPLICATE txn=" + r.transactionId() + " request=" + r.requestId() + " action=ignored");
            }
            return TransactionStatus.DUPLICATE;
        }

        AccountState account = accounts.computeIfAbsent(r.accountId(), k -> new AccountState());
        long seq = r.sequenceNumber();
        if (seq < account.nextExpected) {
            reject(r, "sequence " + seq + " already consumed by another transaction");
            return TransactionStatus.REJECTED;
        }
        if (account.buffered.containsKey(seq)) {
            reject(r, "sequence " + seq + " already claimed by another buffered transaction");
            return TransactionStatus.REJECTED;
        }

        idempotencyStore.remember(r.transactionId(), r.requestId());
        TransactionRecord rec = new TransactionRecord(r);
        records.put(r.transactionId(), rec);
        account.buffered.put(seq, rec);
        LOG.info("event=RECEIVED txn=" + r.transactionId() + " acct=" + Masking.account(r.accountId()) + " seq=" + seq);

        if (seq > account.nextExpected) {
            rec.transition(TransactionStatus.PENDING_SEQUENCE, "waiting for sequence " + account.nextExpected);
            LOG.info("event=PENDING_SEQUENCE txn=" + r.transactionId() + " expected=" + account.nextExpected);
        }
        drain(account);
        return rec.status();
    }

    /**
     * Manual recovery of a record that exhausted its retries. The effect is applied at most once because
     * the ledger entry id equals the transactionId. Note: it is applied out of sequence by design.
     */
    public synchronized TransactionStatus replayFailed(String transactionId) {
        TransactionRecord rec = records.get(transactionId);
        if (rec == null) throw new IllegalArgumentException("unknown transaction");
        if (rec.status() != TransactionStatus.FAILED || rec.failureKind() != FailureKind.TRANSIENT_EXHAUSTED) {
            throw new IllegalStateException("only FAILED records with exhausted retries can be replayed");
        }
        rec.resetForReplay();
        LOG.info("event=REPLAY txn=" + transactionId);
        execute(rec);
        return rec.status();
    }

    public synchronized TransactionRecord get(String transactionId) { return records.get(transactionId); }

    public synchronized ProcessingReport report() {
        Map<TransactionStatus, Integer> counts = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus s : TransactionStatus.values()) counts.put(s, 0);
        List<ProcessingReport.FailureDetail> failures = new ArrayList<>();
        for (TransactionRecord rec : records.values()) {
            counts.merge(rec.status(), 1, Integer::sum);
            if (rec.status() == TransactionStatus.FAILED) {
                failures.add(new ProcessingReport.FailureDetail(
                        rec.request().transactionId(), rec.failureKind(), rec.failureReason(), rec.attempts()));
            }
        }
        counts.put(TransactionStatus.DUPLICATE, duplicates);
        counts.put(TransactionStatus.REJECTED, rejections.size());

        List<ProcessingReport.SequenceGap> gaps = new ArrayList<>();
        new TreeMap<>(accounts).forEach((acct, state) -> {
            if (!state.buffered.isEmpty()) {
                gaps.add(new ProcessingReport.SequenceGap(acct, state.nextExpected, new ArrayList<>(state.buffered.keySet())));
            }
        });
        return new ProcessingReport(totalReceived, counts, gaps, failures,
                new ArrayList<>(rejections), ledger.balancesSnapshot(), payloadMismatches);
    }

    // ---- internals ----

    private void reject(TransactionRequest r, String reason) {
        rejections.add("txn=" + r.transactionId() + " reasons=[" + reason + "]");
        LOG.warn("event=REJECTED txn=" + r.transactionId() + " reason=" + reason);
    }

    private void drain(AccountState account) {
        TransactionRecord next;
        while ((next = account.buffered.remove(account.nextExpected)) != null) {
            account.nextExpected++;
            execute(next);
        }
    }

    private void execute(TransactionRecord rec) {
        String txn = rec.request().transactionId();
        while (true) {
            rec.beginAttempt();
            try {
                apply(rec);
                rec.transition(TransactionStatus.PROCESSED, null);
                LOG.info("event=PROCESSED txn=" + txn + " attempts=" + rec.attempts());
                return;
            } catch (TransientFailureException e) {
                if (rec.attempts() >= maxAttempts) {
                    rec.fail(FailureKind.TRANSIENT_EXHAUSTED, "retries exhausted: " + e.getMessage());
                    LOG.warn("event=FAILED txn=" + txn + " kind=TRANSIENT_EXHAUSTED attempts=" + rec.attempts());
                    return;
                }
                rec.transition(TransactionStatus.RETRY_PENDING, e.getMessage());
                LOG.warn("event=RETRY_PENDING txn=" + txn + " attempt=" + rec.attempts() + " reason=" + e.getMessage());
                pause(backoffMillis * rec.attempts());
            } catch (BusinessRuleException e) {
                rec.fail(FailureKind.BUSINESS, e.getMessage());
                LOG.warn("event=FAILED txn=" + txn + " kind=BUSINESS reason=" + e.getMessage());
                return;
            }
        }
    }

    /** Validate and compute first, call the (fallible) downstream hook, then commit atomically in the ledger. */
    private void apply(TransactionRecord rec) {
        TransactionRequest r = rec.request();
        BigDecimal amount = r.amount().setScale(2);
        BigDecimal signed;
        switch (r.transactionType()) {
            case CREDIT -> signed = amount;
            case DEBIT -> {
                if (ledger.balance(r.accountId(), r.currency()).compareTo(amount) < 0) {
                    throw new BusinessRuleException("INSUFFICIENT_FUNDS");
                }
                signed = amount.negate();
            }
            case REVERSAL -> signed = reversalAmount(r, amount);
            default -> throw new BusinessRuleException("UNSUPPORTED_TYPE");
        }

        failureInjector.beforeCommit(rec, rec.attempts());

        boolean posted = ledger.post(new LedgerEntry(r.transactionId(), r.accountId(), r.currency(), signed));
        if (!posted) {
            LOG.info("event=LEDGER_ENTRY_EXISTS txn=" + r.transactionId() + " action=no_second_effect");
        }
        if (r.transactionType() == TransactionType.REVERSAL) {
            reversed.add(r.originalTransactionId());
        }
    }

    private BigDecimal reversalAmount(TransactionRequest r, BigDecimal amount) {
        TransactionRecord original = records.get(r.originalTransactionId());
        if (original == null || original.status() != TransactionStatus.PROCESSED) {
            throw new BusinessRuleException("ORIGINAL_NOT_PROCESSED");
        }
        TransactionRequest o = original.request();
        if (o.transactionType() == TransactionType.REVERSAL || !o.accountId().equals(r.accountId())
                || !o.currency().equals(r.currency())) {
            throw new BusinessRuleException("ORIGINAL_NOT_REVERSIBLE");
        }
        if (reversed.contains(o.transactionId())) {
            throw new BusinessRuleException("ALREADY_REVERSED");
        }
        if (o.amount().compareTo(amount) != 0) {
            throw new BusinessRuleException("REVERSAL_AMOUNT_MISMATCH");
        }
        BigDecimal originalSigned = ledger.find(o.transactionId())
                .orElseThrow(() -> new BusinessRuleException("ORIGINAL_NOT_REVERSIBLE")).signedAmount();
        return originalSigned.negate();
    }

    private void pause(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
