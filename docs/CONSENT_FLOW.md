# Payment Consent Flow

```
create transfer intent
  -> fraud score (allow)
  -> POST /api/v2/payment-providers/open-banking/consents   (consent AWAITING_AUTHORISATION)
  -> redirect customer to authorisationUrl (bank)            (carries state + nonce)
  -> bank redirects back: GET .../callback?state&consent_ref&result
       - state is one-time (UNIQUE) -> replay rejected
       - consent_ref must match the consent for that state
       -> consent AUTHORISED
  -> POST .../consents/{ref}/submit                          (reserve funds + submit to rail)
  -> provider webhook / reconciliation settles or releases   (reused v2.2 external rail)
```

## Consent states
`CREATED → AWAITING_AUTHORISATION → AUTHORISED → SUBMITTED` (happy path); `REJECTED`, `EXPIRED`,
`REVOKED`, `FAILED` are terminal. A consent has a TTL (`trustledger.openbanking.consent-ttl-seconds`,
default 900s); an expired consent cannot be submitted.

## Money movement
`submit` reuses the verified v2.2 external rail: reserve (available → pending) on submit, then
settle (post Debit source / Credit clearing) or release on the provider's outcome. No double-spend,
idempotent, reconciled — see `docs/PROVIDER_RECONCILIATION.md`.
