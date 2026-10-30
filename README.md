# NPS Provider Microservice

NIBSS National Payment Stack (NPS) provider integration using ISO 20022 XML messaging.

## Overview

This microservice handles communication with NIBSS NPS for:
- **Name Enquiry** (acmt.023 → acmt.024) - Account validation before transfer
- **Bank Transfer** (pacs.008 → pacs.002) - FI-to-FI credit transfer
- **Requery** (pacs.028 → pacs.002) - Transaction status query

## API

### HTTP JSON testing interface

HTTP and gRPC use the same `NpsOperations` application layer. The HTTP facade
is disabled by default, enabled in local development and tests, and can be
explicitly enabled with `NPS_HTTP_ENABLED=true`. This switch is not authentication;
keep the testing interface on a restricted development/sandbox network.

| HTTP endpoint | Operation |
|---|---|
| `POST /api/nps/name-enquiry` | Name enquiry |
| `POST /api/nps/transfers` | Bank transfer |
| `POST /api/nps/requery` | Status query |
| `POST /api/nps/execute` | Select an operation using `action` |

Example name-enquiry request (`Content-Type: application/json`):

```json
{
  "reference": "test-name-001",
  "metadata": {
    "account_number": "0123456789",
    "account_name": "JOHN DOE SMITH",
    "bank_code": "000013"
  }
}
```

The generic execute endpoint additionally requires `action`. Transfers use an
exact JSON decimal `amount` and the metadata described below. Named endpoints
select their operation regardless of an `action` supplied in the body.
Responses contain `status`, `message`, `reference`, `providerReference`,
`responseCode`, and structured JSON `data`; XML remains inside the integration
layer. A valid HTTP response is not proof of payment success: inspect `status`.
Malformed JSON/missing bodies return HTTP 400; disabled endpoints return HTTP 404.

### Internal inbound gRPC contract

`src/main/proto/institution_nps_service.proto` defines the proposed internal
`InstitutionNpsService`: `VerifyAccount`, `ProcessInboundCredit`, and
`NotifyPaymentStatus`. Fields mirror NIBSS business data, with exact decimal
amounts and explicit message/correlation IDs. The institution service owns account
verification, payment decisions, ledger effects, and their durable idempotency.
The adapter owns NIBSS translation and transport.

Inbound `acmt.023`, `pacs.008`, and `pacs.002` callbacks now forward to this service
using deadline-bound gRPC. Configure:

```bash
NPS_INTERNAL_ENABLED=true
NPS_INTERNAL_HOST=your-institution-service
NPS_INTERNAL_PORT=9001
NPS_INTERNAL_TIMEOUT_MS=3000
# TLS is the default; enable plaintext only for an appropriate local test environment.
NPS_INTERNAL_PLAINTEXT=false
# Optional bearer credentials; TLS/mTLS trust configuration uses Quarkus gRPC settings.
NPS_INTERNAL_BEARER_TOKEN=your-service-token
```

The adapter validates the receiving member ID against `NPS_SOURCE_ID`, preserves
exact amounts and correlation fields, and supplies a stable SHA-256 idempotency
key derived from message type, sender member ID, and original message ID. The
receiving service must atomically deduplicate that key with its business operation
and reject key reuse with changed data. The adapter journal provides replay and
conflict protection, while financial deduplication remains the service's responsibility.

A verified account result becomes an `acmt.024.001.04` reply. Authorized/rejected
credit decisions become `pacs.002.001.12` replies with `ACSC/AUTH` or `RJCT/NAUT`.
Pending, unspecified, mismatched, unavailable, or deadline-expired decisions return
HTTP 503 without an invented payment outcome. Status callbacks receive HTTP 200
only after a correlated `durably_accepted` receipt from the internal service.

Replies are returned on the current callback HTTP response, signed/encrypted in
normal operation. Sandbox validation must establish whether NIBSS additionally
requires a separate asynchronous submission; that delivery workflow is not added
here. An authorized internal decision must have a durable agreed business meaning;
the adapter cannot infer ledger posting from an enum value alone.

Unconfigured forwarding returns HTTP 503. The old Kafka-only receipt behavior is
available solely under explicit `nps.inbound.mode=legacy-test` with the test crypto
bypass; default operation is `grpc`. The gRPC path does not also publish legacy
credit instructions, avoiding two independent business-processing paths.
Async `acmt.024` and `pain.002` notifications still use the existing Kafka path.
No local account or ledger implementation is introduced.

### Swagger UI for HTTP testing

Run `./mvnw.cmd quarkus:dev` on Windows (or `./mvnw quarkus:dev` on Unix), then open
Swagger UI at `http://localhost:<APP_PORT>/q/swagger-ui/`. The OpenAPI document is
available at `/q/openapi` (JSON with `Accept: application/json`). `APP_PORT`
defaults to 8080, the cluster convention; set a free local port in `.env`.

Expand **NPS JSON operations**, choose an endpoint, click **Try it out**, edit the
example JSON, and click **Execute**. Name enquiry, transfer, requery, and generic
execute routes include editable examples. Replace synthetic accounts, KYC values,
name-enquiry IDs, and original transaction details with actual sandbox values.
The dedicated routes choose the action automatically; `/execute` requires `action`.
Responses use HTTP 200 for business outcomes: inspect `status`, `message`,
`responseCode`, and `data`. Preserve the transfer reference across retries.

Dev/test mode includes Swagger UI, and the JSON facade is enabled in development.
For a packaged test deployment, build with `SWAGGER_UI_ALWAYS_INCLUDE=true`
(Swagger UI inclusion is a build-time setting), and run with `NPS_HTTP_ENABLED=true`.
Swagger does not replace the configured NIBSS credentials, keys, connectivity, or
institution service. Executing an operation calls the configured backend.

### gRPC Service

The service exposes a gRPC endpoint (`ProviderService.Execute`) with the following actions:

#### name-enquiry
Validates a bank account before transfer.

**Metadata:**
- `account_number` (required) - Ten-digit account to validate.
- `account_name` (required) - Expected party name, as required by the guide's verification request.
- `bank_code` (required) - Six-digit destination institution member ID.

Name enquiry addresses the destination institution directly using member IDs and
uses the guide's IBAN account representation. Legacy `channel_code` is accepted
for caller compatibility but is not emitted in acmt.023: the pasted field table
does not define a channel extension for this message. Name lookup without an
expected party name needs explicit NIBSS confirmation.

**Response data_json:**
```json
{
  "accountName": "JOHN DOE",
  "accountNumber": "0123456789",
  "bvn": "22123456789",
  "bankCode": "000013",
  "sessionId": "...",
  "messageId": "original outbound enquiry message ID",
  "responseMessageId": "response assignment message ID",
  "creditorAccountDesignation": "2",
  "creditorIdType": "BVN",
  "creditorIdValue": "22123456789",
  "creditorAccountTier": "3",
  "riskRating": null
}
```

`messageId` and `provider_reference` identify the outbound enquiry request. Pass
that ID as transfer metadata `name_enquiry_msg_id`. Verification fields are returned
from the response without invented defaults; legacy responses may leave them null.
Only verified responses with a returned account number and name can succeed;
a returned account number that differs from the requested one fails validation.

#### bank-transfer
Initiates a fund transfer to another bank.

**Required metadata:**
- `account_number`, `account_name`, `bank_code` ? verified creditor account, name, and six-digit institution member ID.
- `debtor_name`, `debtor_account` ? sender name and ten-digit account number.
- For each of `debtor` and `creditor`: `<party>_account_designation`, `<party>_account_tier`, `<party>_id_type`, `<party>_id_value`.
- `name_enquiry_msg_id` ? successful name-enquiry message ID; separate from the transfer end-to-end ID.
- `channel_code`, `transaction_location` ? actual channel and transaction location; no invented defaults.

Account designations: 1 corporate, 2 individual, 3 joint, 4 other, 5 juvenile, 6 sole proprietorship.
Tiers: 1?3. ID types: `BVN`, `NIN`, `JTBTIN`, `FIRSTIN`, `RC`.
`NIN` requires tier 1; `JTBTIN`, `FIRSTIN`, and `RC` require designation 1.
The guide's ID-value length examples conflict, so exact type-specific ID lengths still require confirmation.

**Optional metadata:** `end_to_end_id`, `narration` (maximum 100 characters).
`session_id`, `debtor_bvn`, and `kyc_level` do not replace the required verification fields.

**Amount:** Positive `amount` in the gRPC request, at most two decimal places; no silent rounding.

Transfer `data_json` returns original message/transaction/end-to-end IDs, creation time,
settlement date, and agents, including after a transport timeout. Persist this data
before relying on requery; the returned data is not a durable journal.

#### requery

Required metadata: `original_msg_id`, `original_tx_id`, `original_creation_time`
(ISO 8601 with offset), `original_settlement_date` (`YYYY-MM-DD`),
`original_instructing_agent`, and `original_instructed_agent` (six-digit member IDs).
End-to-end-only queries are rejected. The builder generates a fresh status-request ID.
Requery remains informational and never establishes payment finality.

### REST Callbacks

NPS sends callbacks to these endpoints for inbound messages:

- `POST /nps/callback/pacs008` - Inbound credit transfer
- `POST /nps/callback/pacs002` - Payment status notification
- `POST /nps/callback/acmt023` - Inbound name enquiry
- `POST /nps/callback/acmt024` - Name enquiry response
- `POST /nps/callback/pain002` - Payment initiation status

## Configuration

### Required Environment Variables

```bash
# NPS Endpoints
NPS_BASE_URL=https://apitest.nibss-plc.com.ng:8022
NPS_TOKEN_URL=https://apitest.nibss-plc.com.ng:1443/reset

# OAuth2 Credentials
NPS_CLIENT_ID=your-client-id
NPS_CLIENT_SECRET=your-client-secret

# Institution Identity
NPS_SOURCE_ID=000000
NPS_SOURCE_BIC=IRPYNGLA
NPS_SOURCE_NAME=IRPAY FINTECH LIMITED

# RSA Keys (for XML signing/encryption)
NPS_PRIVATE_KEY_PATH=/etc/nps/keys/private.pem
NPS_PUBLIC_KEY_PATH=/etc/nps/keys/public.pem
NPS_NIBSS_PUBLIC_KEY_PATH=/etc/nps/keys/nibss-public.pem
NPS_CERTIFICATE_PATH=/etc/nps/keys/certificate.pem

# Kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9094

# Redis (for token caching)
REDIS_HOST=localhost
REDIS_PORT=26379
```

## Security

### XML Signing
All outbound messages are signed using W3C XMLDSIG with RSA-SHA256. The signature is appended to the root `Document` with exclusive C14N for the guide-aligned outbound messages.

### XML Encryption
The contents of the ISO business element (for example `IdVrfctnReq` or `FIToFICstmrCdtTrf`) are encrypted; the root `Document` and sibling signature remain visible. The detailed endpoint examples specify:
- AES-256-GCM for content encryption
- RSA-OAEP (`rsa-oaep-mgf1p`), SHA-1/MGF1-SHA1 defaults, for key wrapping

### OAuth2
Bearer tokens are obtained via client_credentials flow and automatically refreshed before expiry.

## Message Flow

### Outbound Transfer
```
1. Build pacs.008 XML
2. Sign XML (RSA-SHA256, enveloped signature)
3. Encrypt signed XML (AES-256-GCM + RSA-OAEP)
4. POST to NPS with Bearer token
5. Decrypt response
6. Verify signature
7. Parse pacs.002 status
```

### Inbound Transfer (Callback)
```
1. Receive encrypted XML
2. Decrypt XML
3. Verify signature
4. Forward to institution gRPC service for a durable credit decision
5. Build pacs.002 response
6. Sign and encrypt response
7. Return to NPS
```

## Development

### Build
```bash
./mvnw package
```

### Run locally
```bash
./mvnw quarkus:dev
```

### Pre-sandbox behavior and remaining integration work

- Invalid or missing response signatures stop response processing. Transfer results without a verified payment status remain pending; they must not trigger a reversal.
- HTTP 401 refreshes the token and retries once with the identical signed/encrypted payload. The default outbound deadline is 20 seconds (`NPS_TIMEOUT_MS`), shared by token acquisition, token refresh, and HTTP retries.
- `ACTC` and `ACCP` are acknowledgements and remain pending. `ACSP` and `ACSC` represent successful payment statuses; `RJCT` represents rejection. Unknown or malformed statuses remain pending.
- Requery is informational: the gRPC result remains `pending`, `response_code` contains the queried status, and `data_json` includes `finalityConfirmed: false`. Callers must use NPS authorization or settlement reconciliation to establish finality.
- Transfer `provider_reference` identifies the original outbound message, including after an HTTP timeout. Requery requires explicit original identifiers; the internal reference is not substituted for them.
- Callback XML supports namespace prefixes and amount currency attributes. DTDs and external entities are disabled. A negative name-verification result cannot succeed merely because a name is present.
- In default gRPC callback mode, account enquiries, inbound credits, and payment statuses wait for a validated institution-service response. The legacy test path and remaining asynchronous acmt.024/pain.002 notifications use Kafka acknowledgement; their consumers still require durable deduplication.
- RSA private keys may be PKCS#8 or PKCS#1 PEM. Public keys may be SubjectPublicKeyInfo PEM or X.509 certificates. Raw unsigned requests require the explicit test crypto bypass.

The following are still required before a complete payment sandbox test:

1. Connect the deployed institution gRPC service for account eligibility, actual KYC values, durable credit decisions, and payment-status processing. Its ledger must atomically deduplicate by the supplied idempotency key and reject changed business data.
2. Deploy persistent adapter journal storage and validate restart, locking, permissions, capacity, and recovery on the deployment filesystem. Maintain the institution's own durable transaction records and reconciliation process.
3. Confirm the callback reply versus separate asynchronous response-submission contract with NIBSS. The implemented gRPC callback path returns the signed/encrypted response in the callback body.
4. Validate messages against NIBSS-approved XSDs and sandbox responses, including AUTH/NAUT decisions and requery.
5. Validate the selected security profile with NIBSS-issued keys. Confirm OAuth format, connectivity, firewall restrictions, and service authentication.

Focused safety tests run without external services:

```bash
./mvnw -Dtest=NpsClientTest,NpsTokenManagerTest,NpsCryptoTest,NpsInboundParserTest,NpsResponseParserTest,NpsXmlTest,NpsCallbackHandlerTest,ProviderGrpcSafetyTest test
```

The Quarkus API integration tests use WireMock and a test-only acknowledgement sink, so they do not need Docker, Kafka, or Redis. They validate API behavior; they do not certify real broker delivery or NIBSS interoperability. Run `./mvnw test` for the complete local suite.

### Repository hygiene

Keep logs, generated Maven output, local `.env` files, real key material, and
adapter journal records out of Git. `.gitignore` excludes these files, and
`.dockerignore` excludes local runtime data from the Docker build context.
The `src/test/resources/test-*.pem` files are synthetic test fixtures and remain
versioned; never replace them with sandbox or production keys.

Before committing, run `./mvnw spotless:check`, `git diff --check`, and review
`git status --short`. Check tracked logs with `git ls-files '*.log' '*.log.*'`;
that command should return no files. Ignoring a file does not remove it from
existing commits or stop tracking an already committed file.

### Docker
```bash
docker build -t provider-nps .
docker run -p 8080:8080 provider-nps
```

## Kafka Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| client-logs | Outbound | Every NIBSS HTTP exchange, in the logger service's shape; bodies are the signed, encrypted wire payloads |
| nps-inbound-transfers | Outbound | Inbound credit transfer notifications |
| nps-status-updates | Outbound | Async status update notifications |

## Response Codes

| Code | Status | Description |
|------|--------|-------------|
| ACTC | Pending | Accepted technical |
| ACCP | Pending | Accepted customer profile |
| ACSP | Success | Accepted settlement |
| ACSC | Success | Accepted settlement completed |
| PDNG | Pending | Pending |
| RJCT | Failed | Rejected |

## Implementation status

The implementation follows the detailed endpoint samples for the selected XML
security profile: RSA-SHA256 signatures, exclusive canonicalization, AES-256-GCM
business-content encryption, and RSA-OAEP SHA-1/MGF1-SHA1 key wrapping. The FAQ's
SHA-256/CBC wording is retained as a documentation discrepancy; the endpoint
sample profile is the chosen development baseline, without automatic fallback.

Outbound namespaces are `acmt.023.001.04`, `pacs.008.001.12`, and
`pacs.028.001.06`. Builders produce a prefixed `Document` root with unqualified
business children, a UTF-8 XML declaration, and compact XML before signing.
MsgIds contain six source digits, a 14-digit timestamp, and 15 random digits.
Source IDs are rejected if invalid rather than padded or truncated.

Response and callback parsers accept direct `Document` and legacy `BizMsg/Document`
envelopes, including namespace prefixes. They support IBAN and legacy `Othr/Id`
accounts. Malformed report envelopes are rejected before callback publication.
Payment reports retain `AUTH`, `NAUT`, and `NOAN` status IDs and proprietary
reasons separately from group/transaction status. Conflicting authorization and
payment statuses cannot confirm success or rejection.

Shared outbound routes follow the explicit Endpoint URL text in the samples.
Operation-specific alternatives remain configurable with `NPS_NAME_ENQUIRY_PATH`,
`NPS_CREDIT_TRANSFER_PATH`, `NPS_REQUERY_PATH`, and `NPS_PAYMENT_INITIATION_PATH`.
Payment initiation is outside the currently exposed gRPC action scope.

### Duplicate, retry, and recovery behavior

The adapter journal uses `NPS_JOURNAL_DIRECTORY` (default `data/nps-journal`).
Mount this directory on persistent storage. It contains account/payment data and
cached responses: restrict access and apply the institution's encryption-at-rest
and backup policies. There is no automatic expiry; do not delete records to retry
an uncertain payment. Monitor capacity; unavailable/corrupt storage fails closed.

For gRPC-mode inbound name enquiries, credits, and payment statuses, the adapter
records intent before calling the institution service, then stores the validated
reply before acknowledging. Completed duplicates replay the original business
reply without another RPC. Reusing a key with different business XML returns
HTTP 409. Concurrent processing or unavailable internal service/storage returns
HTTP 503. A prepared inbound record can retry the same internal idempotency key;
the institution must atomically deduplicate its financial effects, including a
crash after ledger commit but before the adapter records the reply.

Outbound transfers require a stable caller reference, scoped to the configured
source institution. An identical retry replays the stored result; changed amount
or metadata returns `IDEMPOTENCY_CONFLICT`. Original NIBSS correlation data is
checkpointed before submission. An interrupted or uncertain submission is never
automatically resent: retries return the saved pending details where available,
and callers must requery/reconcile. Requery does not mutate cached submission
results. Generating a new caller reference bypasses this protection; the calling
transaction service must preserve its reference across retries.

Atomic replacement and filesystem locks protect records across journal instances
on a filesystem supporting both. The default local directory is for one adapter
instance; independent replica disks do not coordinate. Multi-node deployment
needs a validated shared store/locking design or a database-backed journal.
Tests cover restart recovery and concurrency, not power-loss durability or a
particular shared filesystem. The adapter does not decide ordering/finality of
status events; original timestamps and statuses go to the institution service.

Signature verification requires one root-level signature covering the whole XML
document with the selected RSA-SHA256/exclusive-C14N profile. Unsigned, tampered,
partial-document, or duplicate signatures are rejected before business processing.

### Remaining work before a compliance claim

- Connect the deployed institution service for actual account verification and
  durable payment decisions; the adapter itself does not own balances or posting.
- Validate the adapter journal on persistent deployment storage and the institution
  service's atomic ledger deduplication and reconciliation. Adapter records and Kafka
  acknowledgements do not establish payment finality.
- Deploy the internal gRPC service implementing account verification, durable
  credit decisions, and payment-status receipt, and validate its authentication,
  deadlines, and idempotency with the adapter. Legacy test-mode receipt builders
  remain isolated from the default gRPC forwarding path.
- Implement Get Participants, which the FAQ lists in the supported scope.
- Validate messages against the approved XSDs and run NIBSS sandbox scenarios with
  real credentials, keys, whitelisted connectivity, and the required OAuth setup.
- Obtain NIBSS technical sign-off before describing the service as certified or
  production-ready.

Passing local tests verifies implemented behavior; it does not establish full
NIBSS compliance. The supplied documentation is the working specification for
continued development.

## License

Proprietary - IRPay Fintech Limited
