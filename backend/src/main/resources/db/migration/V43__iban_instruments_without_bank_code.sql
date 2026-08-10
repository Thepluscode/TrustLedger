-- An IBAN encodes its own bank; a sort code, routing number or NUBAN does not.
-- V23 required bank_code for every BANK_ACCOUNT, which rejected legitimate SEPA payout
-- instruments (BIC is frequently omitted). Widen the constraint for IBAN-formed identifiers only.
--
-- The rule keys on the identifier scheme, not the country: a GB instrument given as a sort code
-- still needs its bank code, while the same beneficiary given as an IBAN does not.
-- Widening only — every row that satisfied the old constraint satisfies this one.

ALTER TABLE payout_instruments DROP CONSTRAINT chk_bank_instrument_code;

ALTER TABLE payout_instruments
    ADD CONSTRAINT chk_bank_instrument_code
        CHECK (instrument_type <> 'BANK_ACCOUNT'
               OR bank_code IS NOT NULL
               OR masked_identifier ~ '^[A-Z]{2}[0-9]{2}');
