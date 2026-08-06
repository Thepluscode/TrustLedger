-- Canonical reconciliation-result classification (the closed taxonomy, PRODUCT_BLUEPRINT §1.3).
-- Specific issue `type` strings remain the detailed vocabulary; `classification` is what exception
-- ops, provider scorecards and the daily report key on. Backfill mirrors
-- ReconciliationClassification.BY_TYPE — keep the two in sync.
ALTER TABLE reconciliation_issues ADD COLUMN classification VARCHAR(32);

UPDATE reconciliation_issues SET classification = CASE type
  WHEN 'SETTLEMENT_LINE_UNMATCHED'      THEN 'MISSING_INTERNAL_RECORD'
  WHEN 'SETTLEMENT_AMOUNT_MISMATCH'     THEN 'AMOUNT_MISMATCH'
  WHEN 'SETTLEMENT_CURRENCY_MISMATCH'   THEN 'CURRENCY_MISMATCH'
  WHEN 'SETTLEMENT_LINE_DUPLICATE'      THEN 'DUPLICATE_TRANSACTION'
  WHEN 'SETTLEMENT_MISSING'             THEN 'MISSING_SETTLEMENT'
  WHEN 'SETTLEMENT_TOTAL_MISMATCH'      THEN 'AMOUNT_MISMATCH'
  WHEN 'EXTERNAL_STATUS_MISMATCH'       THEN 'INVALID_STATE_TRANSITION'
  WHEN 'UNBALANCED_LEDGER_TRANSACTION'  THEN 'AMOUNT_MISMATCH'
  WHEN 'EXPIRED_RESERVATION'            THEN 'INVALID_STATE_TRANSITION'
  ELSE 'UNKNOWN'
END;

ALTER TABLE reconciliation_issues ALTER COLUMN classification SET NOT NULL;

-- The closed vocabulary, enforced at the database (MATCHED excluded: an issue is by definition a break).
ALTER TABLE reconciliation_issues ADD CONSTRAINT chk_reconciliation_classification
  CHECK (classification IN ('MISSING_PROVIDER_RECORD', 'MISSING_INTERNAL_RECORD', 'AMOUNT_MISMATCH',
                            'CURRENCY_MISMATCH', 'FEE_MISMATCH', 'DUPLICATE_TRANSACTION',
                            'MISSING_SETTLEMENT', 'LATE_SETTLEMENT', 'INVALID_STATE_TRANSITION', 'UNKNOWN'));

-- Settlement lines gain the two new match outcomes the ingest now detects.
ALTER TABLE settlement_statement_lines DROP CONSTRAINT chk_settlement_line_match;
ALTER TABLE settlement_statement_lines ADD CONSTRAINT chk_settlement_line_match
  CHECK (match_status IN ('MATCHED', 'UNMATCHED', 'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH', 'DUPLICATE'));
