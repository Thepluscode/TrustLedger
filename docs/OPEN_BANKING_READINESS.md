# Open Banking Readiness (sandbox)

TrustLedger v2.6 is **Open Banking-shaped**, not production-connected. It implements the OB v3.1
domestic-payment concepts against a sandbox provider so a real adapter can drop in later.

## Implemented (sandbox, verified)
- Domestic payment **consent** registration + authorisation URL.
- Verified **redirect callback** (one-time state, consent binding, redirect allowlist, replay rejected).
- **Submit** an authorised consent → reserve funds via the external rail.
- **Settlement / timeout / reconciliation** reused from the v2.2 verified rail (`PENDING_UNKNOWN`,
  late settle once, duplicate-webhook protection, provider mismatch → issue).
- Audit + evidence cover provider events.

## Mapped to OB v3.1 concepts
| OB concept | TrustLedger |
|------------|-------------|
| Register domestic payment consent | `POST /consents` |
| Authorise (redirect to ASPSP) | `authorisationUrl` (state + nonce) |
| Redirect back | `GET /callback` (state-verified) |
| Submit domestic payment | `POST /consents/{ref}/submit` |
| Get payment status | external attempt status + reconciliation |

## Not implemented (scoped out)
Domestic **scheduled** payment, standing orders, international payments — placeholders only.
Real ASPSP credentials, OBIE directory/eIDAS identity, SCA delegation — see
`docs/REGULATORY_BOUNDARIES.md`. Domestic immediate payment sandbox first, by design.
