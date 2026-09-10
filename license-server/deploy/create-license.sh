#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/data/dewu-license}"
DB_PATH="${LICENSE_DB_PATH:-$APP_DIR/data/license.db}"
DAYS="${1:-30}"
CUSTOM_KEY="${2:-}"

if ! [[ "$DAYS" =~ ^[0-9]+$ ]]; then
  echo "Usage: $0 <days> [custom-card-key]"
  echo "days=0 means no expiration"
  exit 1
fi

mkdir -p "$(dirname "$DB_PATH")"

if [[ -n "$CUSTOM_KEY" ]]; then
  CARD_KEY="$CUSTOM_KEY"
else
  CARD_KEY="DEWU-$(openssl rand -hex 4 | tr '[:lower:]' '[:upper:]')-$(openssl rand -hex 4 | tr '[:lower:]' '[:upper:]')"
fi

SAFE_KEY=$(printf "%s" "$CARD_KEY" | sed "s/'/''/g")

if [[ "$DAYS" == "0" ]]; then
  EXPIRES_SQL="NULL"
else
  EXPIRES_SQL="datetime('now', '+${DAYS} days')"
fi

sqlite3 "$DB_PATH" <<SQL
PRAGMA busy_timeout = 5000;
INSERT INTO license_keys(card_key, status, expires_at)
VALUES ('$SAFE_KEY', 'ACTIVE', $EXPIRES_SQL);
SQL

echo "Created license: $CARD_KEY"
if [[ "$DAYS" == "0" ]]; then
  echo "Expires: NEVER"
else
  echo "Expires: +${DAYS} days"
fi
