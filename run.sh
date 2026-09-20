#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${MIGRATOR_DB_PASSWORD:?Please export MIGRATOR_DB_PASSWORD first}"
exec java -jar target/datasphere-ds-migrator.jar
