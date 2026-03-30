#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="${PACKAGE_NAME:-com.termux}"
ANDROID_SERIAL="${ANDROID_SERIAL:-}"
INTERNAL_DIR="${INTERNAL_DIR:-/data/user/0/${PACKAGE_NAME}/files/home/.clawmobile/payload}"
TERMUX_LAYER_PAYLOAD="${TERMUX_LAYER_PAYLOAD:-build/clawmobile-runtime/termux-layer-export-test/termux-prefix-layer.tar}"
ROOTFS_PAYLOAD="${ROOTFS_PAYLOAD:-build/clawmobile-runtime/rootfs-export-test/ubuntu-rootfs-harvested.tar}"

usage() {
  cat <<'EOF'
Usage:
  scripts/clawmobile-stage-runtime.sh

Environment:
  PACKAGE_NAME         Android package name (default: com.termux)
  ANDROID_SERIAL       adb serial override
  INTERNAL_DIR         Device payload directory under the app private data dir
  TERMUX_LAYER_PAYLOAD Local path to termux-prefix-layer.tar
  ROOTFS_PAYLOAD       Local path to ubuntu-rootfs-harvested.tar

This streams the harvested runtime payloads directly into app-private storage so
the launcher button can restore them without the online installer.
EOF
}

ADB=(adb)
if [[ -n "${ANDROID_SERIAL}" ]]; then
  ADB+=(-s "${ANDROID_SERIAL}")
fi

log() {
  printf '[stage] %s\n' "$*"
}

die() {
  printf '[stage] ERROR: %s\n' "$*" >&2
  exit 1
}

adb_cmd() {
  "${ADB[@]}" "$@"
}

require_file() {
  local file="$1"
  [[ -f "${file}" ]] || die "missing payload: ${file}"
}

main() {
  case "${1:-}" in
    "" ) ;;
    -h|--help) usage; exit 0 ;;
    *) die "unexpected argument: ${1}" ;;
  esac

  require_file "${TERMUX_LAYER_PAYLOAD}"
  require_file "${ROOTFS_PAYLOAD}"

  adb_cmd shell "run-as ${PACKAGE_NAME} /system/bin/sh -c 'mkdir -p ${INTERNAL_DIR}'"

  log "streaming termux runtime layer into app-private storage"
  adb_cmd shell \
    "run-as ${PACKAGE_NAME} /system/bin/sh -c 'cat > ${INTERNAL_DIR}/termux-prefix-layer.tar'" \
    < "${TERMUX_LAYER_PAYLOAD}"

  log "streaming ubuntu rootfs payload into app-private storage"
  adb_cmd shell \
    "run-as ${PACKAGE_NAME} /system/bin/sh -c 'cat > ${INTERNAL_DIR}/ubuntu-rootfs-harvested.tar'" \
    < "${ROOTFS_PAYLOAD}"

  adb_cmd shell "run-as ${PACKAGE_NAME} /system/bin/ls -lh ${INTERNAL_DIR}"

  log "done: ${INTERNAL_DIR}"
}

main "$@"
