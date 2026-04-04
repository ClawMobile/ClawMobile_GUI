#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME="${HOME:-/data/data/com.termux/files/home}"
TMPDIR="${TMPDIR:-$PREFIX/tmp}"
PATH="${PREFIX}/bin:/system/bin:/system/xbin:${PATH:-}"

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

ensure_adb() {
  if command -v adb >/dev/null 2>&1; then
    return 0
  fi

  echo "[adb-connect] adb not found. Installing android-tools..."
  pkg install -y android-tools
}

SERIAL="${HOST}:${CONNECT_PORT}"

ensure_adb

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
