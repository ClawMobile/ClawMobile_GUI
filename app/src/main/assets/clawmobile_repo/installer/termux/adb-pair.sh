#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME="${HOME:-/data/data/com.termux/files/home}"
TMPDIR="${TMPDIR:-$PREFIX/tmp}"
PATH="${PREFIX}/bin:/system/bin:/system/xbin:${PATH:-}"

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

ensure_adb() {
  if command -v adb >/dev/null 2>&1; then
    return 0
  fi

  echo "[adb-pair] adb not found. Installing android-tools..."
  pkg install -y android-tools
}

PAIR_TARGET="${HOST}:${PAIR_PORT}"

ensure_adb

echo "[adb-pair] Starting adb server..."
adb start-server >/dev/null 2>&1 || true

echo "[adb-pair] Pairing to ${PAIR_TARGET}..."
adb pair "${PAIR_TARGET}" "${PAIR_CODE}"

echo "[adb-pair] Pairing approved for ${PAIR_TARGET}"
