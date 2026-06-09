# Payment Provider Adapters

Providers sit behind a stable abstraction so TrustLedger never hardcodes one rail.

## Interfaces
- `PaymentRailAdapter` (v2.2): `initiatePayment`, `getPaymentStatus` — submit + authoritative status.
- `OpenBankingSandboxAdapter` (v2.6): `registerDomesticPaymentConsent`, `authorisationUrl` — the
  consent + authorise shape (OB v3.1: register consent → authorise → submit → status).

## Provider types
`SANDBOX_EXTERNAL` (v2.2), `OPEN_BANKING_SANDBOX` (v2.6). Planned: `BANK_TRANSFER_SANDBOX`,
`CARD_PROVIDER_SANDBOX`, `MANUAL_SETTLEMENT`. **No production bank rails** until the regulatory
boundary in `docs/REGULATORY_BOUNDARIES.md` is satisfied.

## Adding a real provider
Implement the same interfaces with the certified provider's SDK + credentials, register it as a
Spring bean keyed by provider name, and wire its webhook signature scheme into `WebhookSigner`'s
equivalent. The rest of TrustLedger (consent, reserve/settle/release, reconciliation, evidence) is
unchanged — that is the point of the abstraction.
