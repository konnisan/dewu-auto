#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/data/dewu-license}"
DB_PATH="${LICENSE_DB_PATH:-$APP_DIR/data/license.db}"

if [[ ! -f "$DB_PATH" ]]; then
  echo "Database not found: $DB_PATH"
  exit 1
fi

sqlite3 -header -column "$DB_PATH" <<'SQL'
SELECT
  card_key,
  status,
  COALESCE(bound_device_id, '') AS bound_device_id,
  COALESCE(expires_at, 'NEVER') AS expires_at,
  created_at,
  updated_at
FROM license_keys
ORDER BY id DESC;
SQL
