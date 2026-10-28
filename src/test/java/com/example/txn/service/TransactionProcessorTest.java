package com.example.txn.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.txn.model.FailureKind;
import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionStatus;
import com.example.txn.model.TransactionType;
import com.example.txn.store.InMemoryIdempotencyStore;
import com.example.txn.store.Ledger;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransactionProcessorTest {
    private static final String ACC = "ACC-1001";

    private Ledger ledger;
    private ScriptedFailureInjector injector;
    private TransactionProcessor processor;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        injector = new ScriptedFailureInjector();
        processor = new TransactionProcessor(new InMemoryIdempotencyStore(), ledger, injector, 3, 0);
    }

    private static TransactionRequest req(String txn, String reqId, String acct, TransactionType type,
                                          String amount, long seq, String original) {
        return new TransactionRequest(txn, reqId, acct, type, amount == null ? null : new BigDecimal(amount),
                "USD", seq, original);
    }

    private static TransactionRequest credit(String txn, String amount, long seq) {
        return req(txn, "R-" + txn, ACC, TransactionType.CREDIT, amount, seq, null);
    }

    private static TransactionRequest debit(String txn, String amount, long seq) {
        return req(txn, "R-" + txn, ACC, TransactionType.DEBIT, amount, seq, null);
    }

    private BigDecimal balance() { return ledger.balance(ACC, "USD"); }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    // ---- duplicates ----

    @Test
    void duplicateTransactionIdHasNoSecondEffect() {
        processor.submit(credit("T1", "100.00", 1));
        processor.submit(req("T1", "R-other", ACC, TransactionType.CREDIT, "100.00", 1, null));

        assertMoney("100.00", balance());
        assertEquals(1, ledger.entries().size());
        assertEquals(1, processor.report().countsByStatus().get(TransactionStatus.DUPLICATE));
        assertEquals(1, processor.report().countsByStatus().get(TransactionStatus.PROCESSED));
    }

    @Test
    void duplicateDetectedWhenItArrivesLateInTheBatch() {
        processor.processAll(List.of(credit("T1", "10.00", 1), credit("T2", "20.00", 2),
                credit("T3", "30.00", 3), credit("T1", "10.00", 1)));

        assertMoney("60.00", balance());
        assertEquals(1, processor.report().countsByStatus().get(TransactionStatus.DUPLICATE));
    }

    @Test
    void reusedRequestIdWithDifferentTransactionIsDuplicate() {
        processor.submit(credit("T1", "100.00", 1));
        processor.submit(req("T2", "R-T1", ACC, TransactionType.CREDIT, "5.00", 2, null));

        assertMoney("100.00", balance());
        assertNull(processor.get("T2"));
    }

    @Test
    void duplicateWithDifferentPayloadKeepsFirstWriteAndIsCounted() {
        processor.submit(credit("T1", "100.00", 1));
        processor.submit(req("T1", "R-x", ACC, TransactionType.CREDIT, "999.00", 1, null));

        assertMoney("100.00", balance());
        assertEquals(1, processor.report().payloadMismatches());
    }

    // ---- ordering ----

    @Test
    void outOfOrderRequestsAreBufferedThenProcessedInSequence() {
        processor.submit(credit("T3", "50.00", 3));
        assertEquals(TransactionStatus.PENDING_SEQUENCE, processor.get("T3").status());
        processor.submit(debit("T2", "30.00", 2));          // still waiting for seq 1
        assertEquals(TransactionStatus.PENDING_SEQUENCE, processor.get("T2").status());
        assertMoney("0.00", balance());

        processor.submit(credit("T1", "100.00", 1));        // gap fills, 1 -> 2 -> 3 run in order

        assertEquals(TransactionStatus.PROCESSED, processor.get("T1").status());
        assertEquals(TransactionStatus.PROCESSED, processor.get("T2").status());
        assertEquals(TransactionStatus.PROCESSED, processor.get("T3").status());
        assertMoney("120.00", balance());
    }

    @Test
    void orderMattersForBusinessRulesDebitWaitsForFunds() {
        processor.submit(debit("T2", "30.00", 2));
        processor.submit(credit("T1", "100.00", 1));
        assertEquals(TransactionStatus.PROCESSED, processor.get("T2").status());   // would fail if run before T1
        assertMoney("70.00", balance());
    }

    @Test
    void missingSequenceIsReportedAsGap() {
        processor.submit(credit("T1", "10.00", 1));
        processor.submit(credit("T3", "10.00", 3));

        ProcessingReport report = processor.report();
        assertEquals(1, report.sequenceGaps().size());
        assertEquals(2L, report.sequenceGaps().get(0).expectedSequence());
        assertEquals(List.of(3L), report.sequenceGaps().get(0).bufferedSequences());
        assertEquals(1, report.countsByStatus().get(TransactionStatus.PENDING_SEQUENCE));
    }

    @Test
    void staleSequenceFromDifferentTransactionIsRejected() {
        processor.submit(credit("T1", "10.00", 1));
        processor.submit(credit("T9", "10.00", 1));

        assertMoney("10.00", balance());
        assertEquals(1, processor.report().countsByStatus().get(TransactionStatus.REJECTED));
    }

    @Test
    void accountsAreIndependent() {
        processor.submit(req("A1", "RA1", "ACC-2002", TransactionType.CREDIT, "5.00", 1, null));
        processor.submit(credit("T1", "10.00", 1));
        assertMoney("10.00", balance());
        assertMoney("5.00", ledger.balance("ACC-2002", "USD"));
    }

    // ---- invalid input ----

    @Test
    void invalidRecordsAreIsolatedAndDoNotStopTheBatch() {
        TransactionRequest bad = req("T0", "R0", ACC, TransactionType.CREDIT, null, 1, null);
        TransactionRequest negative = req("T00", "R00", ACC, TransactionType.CREDIT, "-5", 1, null);
        processor.processAll(List.of(bad, negative, credit("T1", "10.00", 1)));

        assertMoney("10.00", balance());
        assertEquals(2, processor.report().countsByStatus().get(TransactionStatus.REJECTED));
        assertEquals(1, processor.report().countsByStatus().get(TransactionStatus.PROCESSED));
    }

    // ---- retries ----

    @Test
    void transientFailureIsRetriedAndAppliedExactlyOnce() {
        injector.failTimes("T1", 2);
        processor.submit(credit("T1", "100.00", 1));

        assertEquals(TransactionStatus.PROCESSED, processor.get("T1").status());
        assertEquals(3, processor.get("T1").attempts());
        assertMoney("100.00", balance());
        assertEquals(1, ledger.entries().size());
        assertTrue(processor.get("T1").history().contains("RETRY_PENDING(simulated downstream timeout)"));
    }

    @Test
    void retryExhaustionMarksFailedWithNoPartialEffect() {
        injector.failAlways("T1");
        processor.submit(credit("T1", "100.00", 1));

        assertEquals(TransactionStatus.FAILED, processor.get("T1").status());
        assertEquals(FailureKind.TRANSIENT_EXHAUSTED, processor.get("T1").failureKind());
        assertEquals(3, processor.get("T1").attempts());
        assertMoney("0.00", balance());
        assertTrue(ledger.entries().isEmpty());
    }

    @Test
    void laterSequencesStillProcessAfterAFailedRecord() {
        injector.failAlways("T1");
        processor.submit(credit("T1", "100.00", 1));
        processor.submit(credit("T2", "20.00", 2));

        assertEquals(TransactionStatus.PROCESSED, processor.get("T2").status());
        assertMoney("20.00", balance());
    }

    @Test
    void replayRecoversFailedRecordExactlyOnce() {
        injector.failAlways("T1");
        processor.submit(credit("T1", "100.00", 1));
        injector.heal("T1");

        assertEquals(TransactionStatus.PROCESSED, processor.replayFailed("T1"));
        assertMoney("100.00", balance());
        assertThrows(IllegalStateException.class, () -> processor.replayFailed("T1"));   // second replay refused
        assertMoney("100.00", balance());
        assertEquals(1, ledger.entries().size());
    }

    // ---- business failures and reversals ----

    @Test
    void insufficientFundsFailsWithoutRetryAndWithoutEffect() {
        processor.submit(credit("T1", "10.00", 1));
        processor.submit(debit("T2", "50.00", 2));

        assertEquals(TransactionStatus.FAILED, processor.get("T2").status());
        assertEquals(FailureKind.BUSINESS, processor.get("T2").failureKind());
        assertEquals("INSUFFICIENT_FUNDS", processor.get("T2").failureReason());
        assertEquals(1, processor.get("T2").attempts());
        assertMoney("10.00", balance());
    }

    @Test
    void reversalUndoesOriginalOnlyOnce() {
        processor.submit(credit("T1", "100.00", 1));
        processor.submit(debit("T2", "30.00", 2));
        processor.submit(req("T3", "R-T3", ACC, TransactionType.REVERSAL, "30.00", 3, "T2"));
        assertMoney("100.00", balance());

        processor.submit(req("T4", "R-T4", ACC, TransactionType.REVERSAL, "30.00", 4, "T2"));
        assertEquals(TransactionStatus.FAILED, processor.get("T4").status());
        assertEquals("ALREADY_REVERSED", processor.get("T4").failureReason());
        assertMoney("100.00", balance());
    }

    @Test
    void reversalOfUnknownTransactionFails() {
        processor.submit(req("T1", "R-T1", ACC, TransactionType.REVERSAL, "30.00", 1, "NOPE"));
        assertEquals("ORIGINAL_NOT_PROCESSED", processor.get("T1").failureReason());
    }

    // ---- summary ----

    @Test
    void summaryCountsEveryInboundRequestExactlyOnce() {
        injector.failAlways("T4");
        processor.processAll(List.of(
                credit("T1", "100.00", 1),
                credit("T1", "100.00", 1),                                              // duplicate
                req("BAD", "RB", ACC, TransactionType.CREDIT, null, 2, null),           // rejected
                debit("T2", "500.00", 2),                                               // business failure
                credit("T3", "5.00", 3),
                credit("T4", "5.00", 4),                                                // retries exhausted
                credit("T6", "5.00", 6)));                                              // buffered (gap at 5)

        var counts = processor.report().countsByStatus();
        assertEquals(7, processor.report().totalReceived());
        assertEquals(2, counts.get(TransactionStatus.PROCESSED));
        assertEquals(1, counts.get(TransactionStatus.DUPLICATE));
        assertEquals(1, counts.get(TransactionStatus.REJECTED));
        assertEquals(2, counts.get(TransactionStatus.FAILED));
        assertEquals(1, counts.get(TransactionStatus.PENDING_SEQUENCE));
        assertEquals(7, counts.values().stream().mapToInt(Integer::intValue).sum());
    }
}
