#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR_SOURCE="$PROJECT_DIR/target/dewu-license-server.jar"

PROD_DIR="${PROD_DIR:-/data/dewu-license}"
TEST_DIR="${TEST_DIR:-/data/dewu-license-test}"

if [[ ! -f "$JAR_SOURCE" ]]; then
  echo "Jar not found: $JAR_SOURCE"
  echo "Build first: cd $PROJECT_DIR && mvn clean package -DskipTests"
  exit 1
fi

prepare_env() {
  local target_dir="$1"
  local env_template="$2"

  mkdir -p "$target_dir/data" "$target_dir/logs"
  cp "$JAR_SOURCE" "$target_dir/app.jar"
  cp "$SCRIPT_DIR/restart.sh" "$target_dir/restart.sh"
  chmod +x "$target_dir/restart.sh"

  if [[ ! -f "$target_dir/license.env" ]]; then
    cp "$SCRIPT_DIR/$env_template" "$target_dir/license.env"
    chmod 600 "$target_dir/license.env"
    echo "Created $target_dir/license.env from $env_template"
  else
    echo "Keeping existing $target_dir/license.env"
  fi
}

prepare_env "$PROD_DIR" "license-prod.env.example"
prepare_env "$TEST_DIR" "license-test.env.example"

echo
echo "Prepared both environments:"
echo "  PROD: $PROD_DIR"
echo "  TEST: $TEST_DIR"
echo
echo "Review license.env files, then start with:"
echo "  $PROD_DIR/restart.sh"
echo "  $TEST_DIR/restart.sh"
