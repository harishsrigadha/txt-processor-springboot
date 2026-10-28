package com.example.txn.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.txn.model.TransactionRequest;
import com.example.txn.model.TransactionType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RequestValidatorTest {
    private static TransactionRequest valid() {
        return new TransactionRequest("T1", "R1", "ACC-1", TransactionType.CREDIT, new BigDecimal("10.50"), "USD", 1L, null);
    }

    @Test
    void validRequestHasNoErrors() {
        assertTrue(RequestValidator.validate(valid()).isEmpty());
    }

    @Test
    void nullRequestIsRejected() {
        assertFalse(RequestValidator.validate(null).isEmpty());
    }

    @Test
    void missingMandatoryFieldsAreAllReported() {
        TransactionRequest r = new TransactionRequest(null, " ", null, null, null, null, null, null);
        assertEquals(7, RequestValidator.validate(r).size());
    }

    @Test
    void amountMustBePositiveWithAtMostTwoDecimals() {
        assertFalse(RequestValidator.validate(new TransactionRequest("T", "R", "A", TransactionType.CREDIT,
                BigDecimal.ZERO, "USD", 1L, null)).isEmpty());
        assertFalse(RequestValidator.validate(new TransactionRequest("T", "R", "A", TransactionType.CREDIT,
                new BigDecimal("1.234"), "USD", 1L, null)).isEmpty());
        assertTrue(RequestValidator.validate(new TransactionRequest("T", "R", "A", TransactionType.CREDIT,
                new BigDecimal("1.200"), "USD", 1L, null)).isEmpty());
    }

    @Test
    void currencyAndSequenceAreChecked() {
        assertFalse(RequestValidator.validate(new TransactionRequest("T", "R", "A", TransactionType.CREDIT,
                BigDecimal.ONE, "usd", 1L, null)).isEmpty());
        assertFalse(RequestValidator.validate(new TransactionRequest("T", "R", "A", TransactionType.CREDIT,
                BigDecimal.ONE, "USD", 0L, null)).isEmpty());
    }

    @Test
    void reversalRequiresOriginalTransactionId() {
        assertFalse(RequestValidator.validate(new TransactionRequest("T", "R", "A", TransactionType.REVERSAL,
                BigDecimal.ONE, "USD", 1L, null)).isEmpty());
    }
}
