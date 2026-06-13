-- Count successful transfers per device so the intelligence layer can auto-trust a device after a
-- configurable number of them (trust-after-N): a trusted device stops adding the new-device risk.
ALTER TABLE device_fingerprints ADD COLUMN transfer_count INT NOT NULL DEFAULT 0;
