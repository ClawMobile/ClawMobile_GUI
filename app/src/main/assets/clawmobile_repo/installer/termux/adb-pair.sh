#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

HOST="${1:-127.0.0.1}"
PAIR_PORT="${2:-}"
PAIR_CODE="${3:-}"

usage() {
  echo "Usage:"
  echo "  ./installer/termux/adb-pair.sh <HOST> <PAIR_PORT> <PAIRING_CODE>"
  echo
  echo "Example:"
  echo "  ./installer/termux/adb-pair.sh 127.0.0.1 37123 ABCD1234"
  exit 1
}

if [[ -z "${PAIR_PORT}" || -z "${PAIR_CODE}" ]]; then
  usage
fi

PAIR_TARGET="${HOST}:${PAIR_PORT}"

echo "[adb-pair] Starting adb server..."
adb start-server >/dev/null 2>&1 || true

echo "[adb-pair] Pairing to ${PAIR_TARGET}..."
adb pair "${PAIR_TARGET}" "${PAIR_CODE}"

echo "[adb-pair] Pairing approved for ${PAIR_TARGET}"
