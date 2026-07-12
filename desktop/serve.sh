#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE — copy desktop/.env.example and fill in paths." >&2
  exit 1
fi

set -a
# shellcheck source=.env
source "$ENV_FILE"
set +a

: "${THOTH_MODEL:?THOTH_MODEL must be set in $ENV_FILE}"
: "${THOTH_ZIM:?THOTH_ZIM must be set in $ENV_FILE}"
: "${THOTH_PORT:=8080}"
: "${THOTH_OUT:=$SCRIPT_DIR/../thoth-out}"
: "${THOTH_LOG:=$SCRIPT_DIR/server.log}"

mkdir -p "$(dirname "$THOTH_LOG")"

REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Starting Thoth desktop server on port $THOTH_PORT — log: $THOTH_LOG"

nohup env \
  THOTH_MODEL="$THOTH_MODEL" \
  THOTH_ZIM="$THOTH_ZIM" \
  THOTH_PORT="$THOTH_PORT" \
  THOTH_OUT="$THOTH_OUT" \
  "$REPO_ROOT/gradlew" :desktop:run --args="serve" \
  >> "$THOTH_LOG" 2>&1 &

echo "PID $! — tail -f $THOTH_LOG"
