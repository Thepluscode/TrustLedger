# Provider credential rotation

## Purpose

Provider credentials are control-plane identities. TrustLedger stores only opaque secret-manager references and resolves secret values in memory at execution time.

A mutable secret pointer is not sufficient for production rotation because it cannot represent:

- a candidate key that must not execute yet;
- the exact active version;
- verification overlap during propagation;
- revoked and retired history;
- stale or concurrent activation attempts;
- evidence showing who changed what and when.

## Credential model

Each tenant provider configuration has independent version streams for:

- `API` — provider API authentication and, for Paystack, webhook signature verification;
- `WEBHOOK` — a dedicated webhook-signing secret for providers that support one.

States:

```text
PENDING -> ACTIVE -> GRACE -> RETIRED
    |         |
    +---------+-> REVOKED
```

Rules:

1. A configuration and purpose can have exactly one `ACTIVE` version.
2. `PENDING` versions never authenticate outbound money movement.
3. Rotation moves the old `ACTIVE` version to verification-only `GRACE`.
4. `GRACE` versions expire automatically and become `RETIRED`.
5. `REVOKED` and `RETIRED` versions are never resolved.
6. Secret references and values are absent from list responses and audit metadata.
7. Secret values are resolved only during activation validation or provider execution.

## API

All endpoints require `PROVIDER_CONFIG_MANAGE`.

### Create a pending version

```text
POST /api/v1/tenant/provider-configs/{configId}/credentials

{
  "purpose": "API",
  "secretRef": "vault://payments/paystack/api-v2"
}
```

The response contains version metadata only. The new version remains non-executable.

### List version metadata

```text
GET /api/v1/tenant/provider-configs/{configId}/credentials
```

Returned fields:

- version ID;
- provider configuration ID;
- purpose;
- monotonically increasing version number;
- status;
- activation, grace-expiry, revocation, and creation timestamps.

No secret reference or value is returned.

### Activate with compare-and-swap

```text
POST /api/v1/tenant/provider-configs/{configId}/credentials/{credentialId}/activate

{
  "expectedActiveCredentialId": "<currently-active-version-id>",
  "graceSeconds": 300
}
```

Activation locks the provider configuration and credential rows. The request succeeds only when `expectedActiveCredentialId` still matches the current active version. A stale administrator or automation request fails rather than overwriting a newer rotation.

`graceSeconds` may be 0–604800. Zero performs an immediate cutover and retires the previous version in the same transaction.

Before activation, TrustLedger resolves the candidate reference and rejects an empty or unavailable secret.

### Revoke

```text
POST /api/v1/tenant/provider-configs/{configId}/credentials/{credentialId}/revoke
```

Revoking a pending or grace version removes that version from use.

Revoking the active version fails closed:

- the active compatibility pointer is cleared;
- the provider configuration is disabled;
- the emergency-disable flag is set;
- outbound execution cannot resolve a credential.

Reactivation requires a new pending version, explicit activation, and explicit provider-control recovery.

## Resolution semantics

### Outbound execution

Provider adapters request exactly one `ACTIVE` credential. They never fall back to `GRACE`.

### Webhook verification

Verification may use:

1. the current `ACTIVE` credential;
2. unexpired `GRACE` credentials, newest first.

An unavailable grace reference is skipped with a version-ID-only warning. An unavailable active reference fails verification/execution.

Paystack signs transfer webhooks with the account secret, so its webhook verifier uses the `API` version stream. Other adapters may use the `WEBHOOK` stream.

## Compatibility projection

`tenant_provider_configs.credentials_secret_ref` and `webhook_secret_ref` remain as compatibility projections of the active version. New code resolves through `ProviderCredentialResolver`; the projected columns support existing routing readiness checks and a staged migration away from mutable pointers.

Flyway V27 converts existing references into deterministic version-1 `ACTIVE` rows.

## Audit evidence

TrustLedger records:

- credential version creation;
- activation and previous version ID;
- grace duration and expiry;
- automatic grace retirement;
- revocation and whether fail-closed provider disablement occurred.

Evidence contains credential IDs, purpose, version number, state, timestamps, actor, and provider configuration ID. It excludes secret references and values.

## Production runbook

1. Create the new secret in the external secret manager.
2. Create a `PENDING` version in TrustLedger.
3. Confirm the displayed expected active version.
4. Activate with an overlap appropriate to provider propagation and webhook retry windows.
5. Confirm new outbound sandbox requests use the active version.
6. Confirm callbacks signed by both active and grace versions verify during overlap.
7. Allow automatic grace retirement or revoke the old version after validation.
8. Investigate any activation conflict rather than retrying with a guessed active ID.
9. Use active revocation only as an emergency fail-closed action.

The global production payout kill switch remains disabled by default. Credential rotation does not activate production money movement.
