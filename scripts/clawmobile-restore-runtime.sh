#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="${PACKAGE_NAME:-com.termux}"
ANDROID_SERIAL="${ANDROID_SERIAL:-}"

TERMUX_REL_HOME_DIR="./files/home"
TERMUX_REL_FILES_DIR="./files"
TERMUX_REL_PREFIX_DIR="./files/usr"
PROOT_REL_ROOTFS_DIR="${TERMUX_REL_PREFIX_DIR}/var/lib/proot-distro/installed-rootfs"

usage() {
  cat <<'EOF'
Usage:
  scripts/clawmobile-restore-runtime.sh apply-repo <payload_dir>
  scripts/clawmobile-restore-runtime.sh apply-termux-layer <payload_dir>
  scripts/clawmobile-restore-runtime.sh apply-openclaw <payload_dir>
  scripts/clawmobile-restore-runtime.sh apply-rootfs <payload_dir>
  scripts/clawmobile-restore-runtime.sh activate <payload_dir>
  scripts/clawmobile-restore-runtime.sh apply-all <payload_dir>

Expected payload files inside <payload_dir>:
  clawmobile-repo.tar
  termux-prefix-layer.tar
  openclaw-runtime.tar
  ubuntu-rootfs-harvested.tar

Notes:
  - This script streams local tar payloads into the installed Termux app via `adb run-as`.
  - It does not clear existing data first.
  - `activate` fills in runtime directories that are not worth storing in payloads.
  - Use it only against a target app state you are willing to overwrite.
EOF
}

ADB=(adb)
if [[ -n "${ANDROID_SERIAL}" ]]; then
  ADB+=(-s "${ANDROID_SERIAL}")
fi

log() {
  printf '[restore] %s\n' "$*"
}

die() {
  printf '[restore] ERROR: %s\n' "$*" >&2
  exit 1
}

adb_cmd() {
  "${ADB[@]}" "$@"
}

run_as_direct() {
  adb_cmd shell run-as "${PACKAGE_NAME}" "$@"
}

require_device() {
  local state
  state="$(adb_cmd get-state 2>/dev/null || true)"
  [[ "${state}" == "device" ]] || die "adb device is not ready"
}

require_run_as() {
  adb_cmd shell run-as "${PACKAGE_NAME}" /system/bin/true >/dev/null 2>&1 \
    || die "run-as ${PACKAGE_NAME} failed; make sure the app is installed and debuggable"
}

require_file() {
  local file="$1"
  [[ -f "${file}" ]] || die "missing payload: ${file}"
}

extract_tar_into_app() {
  local tar_file="$1"
  local relative_dir="$2"

  require_file "${tar_file}"
  log "extracting $(basename "${tar_file}") -> ${relative_dir}"
  run_as_direct /system/bin/mkdir -p "${relative_dir}"
  adb_cmd exec-in run-as "${PACKAGE_NAME}" /system/bin/tar -C "${relative_dir}" -xf - < "${tar_file}"
}

apply_repo() {
  local payload_dir="$1"
  extract_tar_into_app "${payload_dir}/clawmobile-repo.tar" "${TERMUX_REL_HOME_DIR}"
}

apply_termux_layer() {
  local payload_dir="$1"
  extract_tar_into_app "${payload_dir}/termux-prefix-layer.tar" "${TERMUX_REL_FILES_DIR}"
}

apply_openclaw() {
  local payload_dir="$1"
  extract_tar_into_app "${payload_dir}/openclaw-runtime.tar" "${PROOT_REL_ROOTFS_DIR}"
}

apply_rootfs() {
  local payload_dir="$1"
  extract_tar_into_app "${payload_dir}/ubuntu-rootfs-harvested.tar" "${PROOT_REL_ROOTFS_DIR}"
}

activate_runtime() {
  local payload_dir="$1"

  log "activating restored runtime"
  run_as_direct /system/bin/mkdir -p \
    "${TERMUX_REL_HOME_DIR}/.termux" \
    "${TERMUX_REL_HOME_DIR}/.ssh" \
    "${TERMUX_REL_PREFIX_DIR}/tmp" \
    "${TERMUX_REL_PREFIX_DIR}/var/lib/proot-distro/dlcache"
  run_as_direct /system/bin/chmod 700 "${TERMUX_REL_PREFIX_DIR}/tmp"

  if [[ -f "${payload_dir}/clawmobile-repo.tar" ]]; then
    log "syncing repo payload into ubuntu root home"
    run_as_direct /system/bin/mkdir -p "${PROOT_REL_ROOTFS_DIR}/ubuntu/root"
    adb_cmd exec-in run-as "${PACKAGE_NAME}" /system/bin/tar -C "${PROOT_REL_ROOTFS_DIR}/ubuntu/root" -xf - < "${payload_dir}/clawmobile-repo.tar"
  fi
}

main() {
  local command="${1:-}"
  local payload_dir="${2:-}"

  case "${command}" in
    apply-repo|apply-termux-layer|apply-openclaw|apply-rootfs|activate|apply-all) ;;
    ""|-h|--help) usage; exit 0 ;;
    *) die "unknown command: ${command}" ;;
  esac

  [[ -n "${payload_dir}" ]] || die "payload_dir is required"
  [[ -d "${payload_dir}" ]] || die "payload_dir does not exist: ${payload_dir}"

  require_device
  require_run_as

  case "${command}" in
    apply-repo)
      apply_repo "${payload_dir}"
      ;;
    apply-termux-layer)
      apply_termux_layer "${payload_dir}"
      ;;
    apply-openclaw)
      apply_openclaw "${payload_dir}"
      ;;
    apply-rootfs)
      apply_rootfs "${payload_dir}"
      ;;
    activate)
      activate_runtime "${payload_dir}"
      ;;
    apply-all)
      apply_termux_layer "${payload_dir}"
      apply_repo "${payload_dir}"
      apply_rootfs "${payload_dir}"
      activate_runtime "${payload_dir}"
      ;;
  esac

  log "done"
}

main "$@"
