# NPS Provider Microservice

NIBSS National Payment Stack (NPS) provider integration using ISO 20022 XML messaging.

## Overview

This microservice handles communication with NIBSS NPS for:
- **Name Enquiry** (acmt.023 → acmt.024) - Account validation before transfer
- **Bank Transfer** (pacs.008 → pacs.002) - FI-to-FI credit transfer
- **Requery** (pacs.028 → pacs.002) - Transaction status query

## API

### gRPC Service

The service exposes a gRPC endpoint (`ProviderService.Execute`) with the following actions:

#### name-enquiry
Validates a bank account before transfer.

**Metadata:**
- `account_number` (required) - Account to validate
- `bank_code` (required) - Destination bank NIP code
- `channel_code` (optional, default: "1") - NIP channel code

**Response data_json:**
```json
{
  "accountName": "JOHN DOE",
  "accountNumber": "0123456789",
  "bvn": "22123456789",
  "bankCode": "000013",
  "sessionId": "...",
  "messageId": "..."
}
```

#### bank-transfer
Initiates a fund transfer to another bank.

**Metadata:**
- `account_number` (required) - Creditor account
- `account_name` (required) - Creditor name (from name enquiry)
- `bank_code` (required) - Creditor bank code
- `debtor_name` (required) - Debtor/sender name
- `debtor_account` (required) - Debtor/sender account
- `debtor_bvn` (optional) - Debtor BVN
- `narration` (optional) - Transaction narration
- `session_id` (optional) - Session ID from name enquiry
- `channel_code` (optional, default: "1")
- `kyc_level` (optional, default: "3")

**Amount:** Set via `amount` field in the gRPC request.

#### requery
Queries the status of a previously submitted transaction.

**Metadata:**
- `original_msg_id` (required if no end_to_end_id) - Original message ID
- `original_end_to_end_id` (optional) - Original end-to-end ID

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
NPS_SOURCE_ID=000000000000
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
All outbound messages are signed using W3C XMLDSIG with RSA-SHA256. The signature is placed in `AppHdr/Sgntr`.

### XML Encryption
Message content (the `Document` element) is encrypted using:
- AES-256-GCM for content encryption
- RSA-OAEP for key wrapping

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
4. Process transfer (credit account)
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

### Docker
```bash
docker build -t provider-nps .
docker run -p 8098:8098 provider-nps
```

## Kafka Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| nps-logs | Outbound | Transaction logs with XML payloads |
| client-logs | Outbound | Client-level events |
| nps-inbound-transfers | Outbound | Inbound credit transfer notifications |
| nps-status-updates | Outbound | Async status update notifications |

## Response Codes

| Code | Status | Description |
|------|--------|-------------|
| ACTC | Success | Accepted technical |
| ACCP | Success | Accepted customer profile |
| ACSP | Success | Accepted settlement |
| ACSC | Success | Accepted settlement completed |
| PDNG | Pending | Pending |
| RJCT | Failed | Rejected |

## License

Proprietary - IRPay Fintech Limited
