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


def duplicate_migration_versions(migration_dir: Path) -> dict[str, list[str]]:
    """Flyway refuses to start when two migrations share a version.

    This is worth a static check rather than leaving it to runtime, because the failure is maximally
    expensive and minimally informative: Flyway runs before the JPA context, so a collision does not
    fail one test — it fails EVERY @SpringBootTest at once with a bean-creation error whose cause is
    several 'Caused by' layers down. It also cannot be found by reading a diff, since the two files
    usually arrive from different branches and neither one looks wrong on its own.

    Seen for real on 2026-08-05: V22__tenant_provider_governance.sql (on main) and
    V22__payment_webhook_envelopes.sql (from a parallel branch) collided, and the whole backend
    stopped booting.
    """
    by_version: dict[str, list[str]] = {}
    for path in sorted(migration_dir.glob('V*__*.sql')):
        version = path.name.split('__', 1)[0][1:]
        by_version.setdefault(version, []).append(path.name)
    return {v: names for v, names in by_version.items() if len(names) > 1}


MIGRATIONS = ROOT / 'backend/src/main/resources/db/migration'
if MIGRATIONS.is_dir():
    found = sorted(MIGRATIONS.glob('V*__*.sql'))
    # A glob that matches nothing would make this check pass while verifying nothing — the schema is
    # not optional, so an empty directory is itself a failure.
    if not found:
        raise SystemExit(f'No migrations found in {MIGRATIONS} — the check would pass vacuously.')
    duplicates = duplicate_migration_versions(MIGRATIONS)
    if duplicates:
        lines = [f'  V{v}: ' + ', '.join(names) for v, names in sorted(duplicates.items())]
        highest = max(int(p.name.split('__', 1)[0][1:]) for p in found)
        raise SystemExit(
            'Duplicate Flyway migration versions — the application will not start:\n'
            + '\n'.join(lines)
            + f'\nRenumber the newer file. Highest version present here is V{highest}; check every '
              'open branch before choosing, since versions are claimed across branches and the next '
              'free number is often higher than this directory alone suggests.')
    print(f'Migration versions unique ({len(found)} migrations, highest '
          f'V{max(int(p.name.split("__", 1)[0][1:]) for p in found)}).')

print('Repository validation passed.')
