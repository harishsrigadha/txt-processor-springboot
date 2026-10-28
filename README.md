# txn-service (Spring Boot)

Idempotent transaction processor exposed as a REST microservice. Java 17, Spring Boot 3.2.5, Maven.
Core logic (validation, idempotency, per-account sequencing, retries, atomic ledger) is unchanged from the plain-Java version.

## Run
    mvn test
    mvn spring-boot:run
    curl -X POST localhost:8080/api/transactions/batch -H "Content-Type: application/json" -d @sample-data/batch.json
    curl localhost:8080/api/report

## Endpoints
| Method | Path | Purpose |
|---|---|---|
| POST | /api/transactions | submit one request, returns {transactionId, status} |
| POST | /api/transactions/batch | submit a JSON array, returns one result per request |
| GET | /api/transactions/{id} | status, attempts, failure info, history |
| POST | /api/transactions/{id}/replay | re-run a FAILED (retries exhausted) record. 404 unknown, 409 not replayable |
| GET | /api/report | text summary: counts, failures, sequence gaps, masked balances |

## Config (application.properties)
txn.max-attempts, txn.backoff-millis, txn.demo-failures (true = T2 fails twice then recovers, T7 always fails; set false for real use).
