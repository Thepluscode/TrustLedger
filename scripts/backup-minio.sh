#!/usr/bin/env bash
# Mirror the evidence object-storage bucket to a backup target using the MinIO client (mc).
# Usage: MC_SRC=myminio/trustledger-evidence MC_DST=backup/trustledger-evidence ./backup-minio.sh
set -euo pipefail

SRC="${MC_SRC:?set MC_SRC=<alias/bucket>}"
DST="${MC_DST:?set MC_DST=<alias/bucket>}"
mc mirror --overwrite --remove "$SRC" "$DST"
echo "Mirrored $SRC -> $DST"
