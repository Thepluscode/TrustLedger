# Callback & Webhook Security

A callback must never directly trust a provider payload.

## Redirect callback (`GET .../callback`)
- **State**: server-generated, `UNIQUE` per consent, **one-time** — a replayed state is rejected (409).
- **Consent reference** must match the consent bound to that state.
- **Redirect allowlist**: the consent's `redirectUrl` is validated against
  `trustledger.openbanking.redirect-allowlist` at creation (unknown origin → 400).
- **Audit**: every callback is recorded (`open_banking_callback_events`).
- Anonymous endpoint (the customer's browser arrives without a JWT) — protected entirely by the
  one-time state + consent binding.

## Provider webhook (`POST /api/v1/payment-rails/webhooks/*`)
- **HMAC-SHA256 signature** verified (`WebhookSigner`); a bad signature → 401, no state change.
- **Idempotency / replay**: `(provider, event_id)` is `UNIQUE`; a duplicate event is ignored —
  a duplicate webhook can never double-post the ledger (verified).

## Why both
Redirect callbacks authenticate the *user's return* (state); webhooks authenticate the *provider's
asynchronous notification* (signature). Settlement only happens through the verified webhook or
reconciliation, never from an unverified redirect.
