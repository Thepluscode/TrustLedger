# Fraud Engine

## Goal

The fraud engine must make explainable decisions before money moves.

It answers:

- allow normally?
- require MFA?
- hold for review?
- reject?
- freeze account?

## Signals

Implemented in the v1.0 domain core:

- high amount anomaly
- new beneficiary
- new device
- failed login velocity
- transfer velocity
- impossible travel
- recent account change
- known bad destination
- blocked recipient hard stop

## Decision thresholds

```text
0-24    ALLOW
25-49   ALLOW_WITH_MONITORING
50-64   STEP_UP_MFA
65-94   HOLD_FOR_REVIEW
95-100  REJECT
```

## Example high-risk pattern

```text
password reset
new device login
new beneficiary
large transfer
impossible travel
```

This should produce one fraud case with linked evidence, not scattered warnings.

## v2.0 improvements

- user risk profiles
- account risk profiles
- device fingerprinting
- linked fraud cases
- beneficiary reputation
- external intelligence feeds
- analyst false-positive feedback
- model-ready feature store later

## What not to do

Do not make ML the first fraud detector. The first system must be deterministic, explainable, testable, and auditable.
