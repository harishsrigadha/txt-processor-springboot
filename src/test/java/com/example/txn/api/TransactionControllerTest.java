package com.example.txn.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "txn.demo-failures=false")
@AutoConfigureMockMvc
class TransactionControllerTest {

    @Autowired
    private MockMvc mvc;

    private static final String CREDIT_A1 = "{\"transactionId\":\"A1\",\"requestId\":\"RA1\",\"accountId\":\"ACC-9001\","
            + "\"transactionType\":\"CREDIT\",\"amount\":10.00,\"currency\":\"USD\",\"sequenceNumber\":1}";

    @Test
    void secondSubmissionOfSameTransactionIsDuplicate() throws Exception {
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(CREDIT_A1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"));

        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(CREDIT_A1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
    }

    @Test
    void unknownTransactionIsNotFound() throws Exception {
        mvc.perform(get("/api/transactions/NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void replayOfUnknownTransactionIsNotFound() throws Exception {
        mvc.perform(post("/api/transactions/NOPE/replay")).andExpect(status().isNotFound());
    }
}
