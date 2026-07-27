-- Invariant 3: ledger entries and transactions are immutable — corrections are reversal entries, never
-- edits or deletes. Enforce this at the data layer (the source of financial truth) so no code path — a
-- cleanup job, an admin hotfix, a future feature, even raw SQL — can ever mutate or delete a posted row.
-- Inserts are unaffected (the trigger fires only on UPDATE/DELETE), so the normal double-entry write path
-- keeps working; a reversal is a new INSERT.

CREATE OR REPLACE FUNCTION trustledger_reject_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Ledger rows are immutable: % on % is not permitted (post a reversal entry instead)',
        TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION trustledger_reject_ledger_mutation();

CREATE TRIGGER ledger_transactions_immutable
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION trustledger_reject_ledger_mutation();
