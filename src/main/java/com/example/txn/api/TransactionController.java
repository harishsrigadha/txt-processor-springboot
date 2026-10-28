package com.example.txn.api;

import com.example.txn.model.TransactionRecord;
import com.example.txn.model.TransactionRequest;
import com.example.txn.service.TransactionProcessor;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private final TransactionProcessor processor;

    public TransactionController(TransactionProcessor processor) {
        this.processor = processor;
    }

    @PostMapping("/transactions")
    public TxnResponse submit(@RequestBody TransactionRequest request) {
        return new TxnResponse(request.transactionId(), processor.submit(request));
    }

    @PostMapping("/transactions/batch")
    public List<TxnResponse> submitBatch(@RequestBody List<TransactionRequest> requests) {
        return requests.stream()
                .map(r -> new TxnResponse(r == null ? null : r.transactionId(), processor.submit(r)))
                .toList();
    }

    @GetMapping("/transactions/{id}")
    public TxnView get(@PathVariable("id") String id) {
        TransactionRecord rec = processor.get(id);
        if (rec == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown transaction");
        }
        return new TxnView(id, rec.status(), rec.attempts(), rec.failureKind(), rec.failureReason(), rec.history());
    }

    @PostMapping("/transactions/{id}/replay")
    public TxnResponse replay(@PathVariable("id") String id) {
        return new TxnResponse(id, processor.replayFailed(id));
    }

    @GetMapping(value = "/report", produces = MediaType.TEXT_PLAIN_VALUE)
    public String report() {
        return processor.report().toText();
    }
}
