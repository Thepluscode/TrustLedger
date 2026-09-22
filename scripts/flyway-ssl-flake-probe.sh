#!/usr/bin/env bash
# Discriminating experiment for the recorded intermittent failure:
#   "Flyway ... Unable to obtain connection ... An error occurred while setting up the SSL connection"
# (FEATURE_TRACKER.md, "Intermittent context-start failure"). It has only been seen locally under
# colima, never in CI. This script does NOT fix or retry anything. It measures.
#
# Hypothesis under test: the container's log says "ready" before the host-side port forwarder can
# relay, so the first connection is accepted and then closed. With the driver's default
# sslmode=prefer, that EOF lands on the SSLRequest and is reported as an SSL error.
#
# Method: start a Postgres container N times. The instant its log reports ready, attempt ONE
# connection with no retry, once with sslmode=prefer and once with sslmode=disable, and record
# the failure class. If failures occur under BOTH modes at similar rates with different messages,
# the cause is the forwarder race, not SSL. If they occur only under prefer, the cause is in SSL
# negotiation. If none occur in N runs, the hypothesis is not supported at this N. Say so.
#
# Usage: DOCKER_HOST=unix://$HOME/.colima/default/docker.sock scripts/flyway-ssl-flake-probe.sh [N]
set -uo pipefail

N="${1:-40}"
IMAGE="postgres:16-alpine"
OUT="${PROBE_OUT:-/tmp/flyway-ssl-flake-probe.tsv}"
command -v docker >/dev/null || { echo "docker not found" >&2; exit 2; }
command -v psql >/dev/null || { echo "psql not found (brew install libpq)" >&2; exit 2; }

printf 'run\tsslmode\toutcome\tmessage\n' > "$OUT"
for i in $(seq 1 "$N"); do
  for mode in prefer disable; do
    cid=$(docker run -d --rm -e POSTGRES_PASSWORD=t -e POSTGRES_USER=t -e POSTGRES_DB=t -p 127.0.0.1::5432 "$IMAGE") || {
      printf '%s\t%s\tDOCKER_RUN_FAILED\t-\n' "$i" "$mode" >> "$OUT"; continue; }
    # Same readiness signal Testcontainers uses for Postgres: the ready line, seen twice.
    until [ "$(docker logs "$cid" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ]; do :; done
    port=$(docker port "$cid" 5432/tcp | head -1 | sed 's/.*://')
    msg=$(PGPASSWORD=t PGCONNECT_TIMEOUT=10 psql "host=127.0.0.1 port=$port user=t dbname=t sslmode=$mode" -Atc 'select 1' 2>&1)
    if [ "$msg" = "1" ]; then outcome=OK; msg=-; else outcome=FAILED; msg=$(echo "$msg" | tr '\n\t' '  ' | cut -c1-160); fi
    printf '%s\t%s\t%s\t%s\n' "$i" "$mode" "$outcome" "$msg" >> "$OUT"
    docker stop -t 0 "$cid" >/dev/null 2>&1
  done
done

echo "results: $OUT"
awk -F'\t' 'NR>1 {k=$2" "$3; c[k]++} END {for (k in c) print c[k], k}' "$OUT" | sort -k2
# An experiment that ran fewer attempts than asked proves nothing about the rate.
ran=$(($(wc -l < "$OUT") - 1)); want=$((N * 2))
[ "$ran" -eq "$want" ] || { echo "INCOMPLETE: $ran of $want attempts recorded" >&2; exit 1; }
