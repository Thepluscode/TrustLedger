-- V51 made a file's bytes its identity inside a case: the same bytes again are a replay. V56 then let
-- feeds deliver into a case, each bound to one provider identity, but kept the V51 key. Two feeds in
-- one case delivering byte-identical bodies therefore collapsed: the second provider's first delivery
-- was counted as a redelivery of the first provider's import and left no record of its own.
--
-- Replay identity is now the source as well as the bytes. A file upload replays an earlier file upload;
-- a feed delivery replays only an earlier delivery to the same feed. Both indexes are strictly looser
-- than the constraint they replace, so every existing row already satisfies them.

ALTER TABLE recon_imports DROP CONSTRAINT uq_recon_import_file;

CREATE UNIQUE INDEX uq_recon_import_file ON recon_imports (tenant_id, case_id, file_sha256)
    WHERE feed_id IS NULL;

CREATE UNIQUE INDEX uq_recon_import_feed_delivery ON recon_imports (tenant_id, case_id, feed_id, file_sha256)
    WHERE feed_id IS NOT NULL;
