# Security Design

## Required controls

- MFA for high-risk actions
- risk-based step-up authentication
- refresh token rotation
- device fingerprinting
- account lockout
- admin RBAC
- tenant isolation
- rate limiting
- audit logging
- secure callbacks
- signed webhooks
- TLS everywhere
- encryption at rest
- secret manager integration

## Sensitive action controls

| Action | Control |
|---|---|
| Large transfer | MFA or fraud hold |
| New beneficiary | Cooling period and MFA |
| Fraud approval | Admin RBAC and audit |
| Account freeze | Senior/admin role |
| Reversal | Dual approval in v2.0 |
| Report export | Audit and permission check |
| Provider webhook | Signature verification |

## PCI scope rule

Do not store raw card numbers. Use provider tokens if card flows are added. Keeping cardholder data out of scope is a deliberate product and security decision.
