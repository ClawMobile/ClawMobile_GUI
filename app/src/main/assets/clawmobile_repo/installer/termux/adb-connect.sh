#!/data/data/ae.clawmobile/files/usr/bin/bash
set -euo pipefail

HOST="${1:-127.0.0.1}"
CONNECT_PORT="${2:-}"

usage() {
  echo "Usage:"
  echo "  ./installer/termux/adb-connect.sh <HOST> <CONNECT_PORT>"
  echo
  echo "Example:"
  echo "  ./installer/termux/adb-connect.sh 127.0.0.1 44557"
  exit 1
}

if [[ -z "${CONNECT_PORT}" ]]; then
  usage
fi

SERIAL="${HOST}:${CONNECT_PORT}"

echo "[adb-connect] Starting adb server..."
adb start-server >/dev/null 2>&1 || true

echo "[adb-connect] Connecting to ${SERIAL}..."
adb connect "${SERIAL}"

echo "[adb-connect] Verifying adb device state..."
if ! adb devices | awk -v serial="${SERIAL}" 'NR > 1 && $1 == serial && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'; then
  echo "[adb-connect] ERROR: ${SERIAL} did not enter device state."
  adb devices || true
  exit 1
fi

echo "[adb-connect] Device linked: ${SERIAL}"
