package com.example.txn.service;

import com.example.txn.model.FailureKind;
import com.example.txn.model.TransactionStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record ProcessingReport(
        int totalReceived,
        Map<TransactionStatus, Integer> countsByStatus,
        List<SequenceGap> sequenceGaps,
        List<FailureDetail> failures,
        List<String> rejections,
        Map<String, BigDecimal> balances,
        int payloadMismatches) {

    public record SequenceGap(String accountId, long expectedSequence, List<Long> bufferedSequences) { }

    public record FailureDetail(String transactionId, FailureKind kind, String reason, int attempts) { }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== PROCESSING SUMMARY =====\n");
        sb.append("Total requests received : ").append(totalReceived).append('\n');
        countsByStatus.forEach((s, n) -> sb.append(String.format("  %-17s: %d%n", s, n)));
        if (payloadMismatches > 0) {
            sb.append("Duplicates with different payload (first write wins): ").append(payloadMismatches).append('\n');
        }
        sb.append("Rejected (invalid input):\n");
        if (rejections.isEmpty()) sb.append("  none\n");
        rejections.forEach(r -> sb.append("  ").append(r).append('\n'));
        sb.append("Failed transactions:\n");
        if (failures.isEmpty()) sb.append("  none\n");
        failures.forEach(f -> sb.append(String.format("  txn=%s kind=%s attempts=%d reason=%s%n",
                f.transactionId(), f.kind(), f.attempts(), f.reason())));
        sb.append("Sequence gaps (buffered, waiting):\n");
        if (sequenceGaps.isEmpty()) sb.append("  none\n");
        sequenceGaps.forEach(g -> sb.append(String.format("  account=%s missing sequence %d, buffered=%s%n",
                Masking.account(g.accountId()), g.expectedSequence(), g.bufferedSequences())));
        sb.append("Balances:\n");
        new TreeMap<>(balances).forEach((k, v) -> {
            String[] p = k.split("\\|");
            sb.append(String.format("  %s %s = %s%n", Masking.account(p[0]), p[1], v.toPlainString()));
        });
        return sb.toString();
    }
}
