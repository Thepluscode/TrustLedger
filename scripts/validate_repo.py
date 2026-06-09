#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
required = [
    'README.md',
    'backend/pom.xml',
    'backend/src/main/java/com/trustledger/TrustLedgerApplication.java',
    'backend/src/main/java/com/trustledger/core/ledger/LedgerService.java',
    'backend/src/main/java/com/trustledger/core/fraud/FraudEngine.java',
    'backend/src/main/java/com/trustledger/core/transfer/TransferOrchestrator.java',
    'docs/TRUSTLEDGER_V2_DESIGN.md',
    'docs/LEDGER_ENGINE.md',
    'docs/FRAUD_ENGINE.md',
    'docs/RECONCILIATION.md',
    'docs/API.md',
    'docs/SECURITY.md',
    'docs/TESTING.md',
    'docs/DATA_MODEL.sql',
    'infra/docker-compose.yml',
    'frontend/package.json',
]
missing = [p for p in required if not (ROOT / p).exists()]
if missing:
    raise SystemExit('Missing required files:\n' + '\n'.join(missing))
print('Repository validation passed.')
