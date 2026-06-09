# Regulatory Boundaries

**Blunt version:**

> TrustLedger's demo/sandbox mode does **not** make the operator a regulated Payment Initiation
> Service Provider (PISP) or Account Information Service Provider (AISP). Production payment
> initiation requires appropriate provider access, compliance review, and regulatory
> authorisation/partnership.

## What this build is

- An Open Banking-**shaped** sandbox: the consent → authorise → callback → submit → reconcile flow,
  modelled on the OB v3.1 payment-initiation concepts, using a fake provider (`OPEN_BANKING_SANDBOX`).
- No real bank credentials, no real money movement, no production rail.

## What it is NOT

- It is not connected to a real ASPSP/bank.
- Operating it does not confer FCA (or equivalent) authorisation.
- The FCA defines a payment initiation service as accessing a user's payment account to initiate
  transfers with consent and authentication — a **regulated** activity. Treat any production
  integration as a regulated path, not a casual API call.

## Before going to production

1. Obtain the right regulatory authorisation or a regulated partner/agent arrangement.
2. Complete provider onboarding (directory, certificates, eIDAS/OBIE-style identity) for the real ASPSP.
3. Compliance review of consent, SCA, data handling, retention, and complaints handling.
4. Replace the sandbox adapter with a certified provider adapter (same `OpenBankingSandboxAdapter` shape).

References: UK Open Banking Standards (api-specifications), FCA AIS/PIS guidance, OB Payment Initiation API v3.1.
