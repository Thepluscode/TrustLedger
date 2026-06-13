-- Persist the originating device on each transfer so an analyst-approved held transfer can feed the
-- full behavioural baseline (device sighting + beneficiary + amount), stopping the same user+payee
-- from being held again. Nullable: historical rows predate this and external payouts may omit it.
ALTER TABLE transfers ADD COLUMN device_id VARCHAR(120);
