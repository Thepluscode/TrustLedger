#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$ROOT_DIR/build/domain-validation"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
find "$ROOT_DIR/backend/src/main/java/com/trustledger/core" -name '*.java' > "$BUILD_DIR/sources.txt"
echo "$ROOT_DIR/backend/src/test/java/com/trustledger/smoke/DomainAcceptanceTestRunner.java" >> "$BUILD_DIR/sources.txt"
javac -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
java -cp "$BUILD_DIR/classes" com.trustledger.smoke.DomainAcceptanceTestRunner
