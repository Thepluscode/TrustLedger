# Architecture

## System view

```text
[Client / Admin UI]
        |
        v
[API Gateway / Spring Security]
        |
        v
[Transfer Service] ---> [Fraud Engine]
        |                  |
        v                  v
[Ledger Service]       [Fraud Case Service]
        |
        v
[PostgreSQL + Outbox] ---> [Redpanda/Kafka]
        |                        |
        v                        v
[Reconciliation]          [Notifications / Reports]
```

## Backend module layout

```text
com.trustledger
  auth
  accounts
  beneficiaries
  transfers
  ledger
  fraud
  fraudcases
  audit
  outbox
  reconciliation
  notifications
  reports
  admin
  common
```

## Deployment topology

Local pilot:

- Spring Boot API
- PostgreSQL
- Redis
- Redpanda
- OpenSearch
- MinIO
- Prometheus
- Grafana
- Next.js frontend

Production-style pilot:

- Nginx reverse proxy
- private application network
- persistent volumes
- health checks
- metrics endpoint
- backup jobs
- explicit secret environment variables

## Service boundary warning

Do not split the ledger into distributed microservices until the domain is stable. The first serious implementation should be a modular monolith with transactional boundaries inside PostgreSQL. Premature microservices around the ledger create distributed consistency failures before you have a customer.
