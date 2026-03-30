#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="${PACKAGE_NAME:-com.termux}"
ANDROID_SERIAL="${ANDROID_SERIAL:-}"
DEFAULT_OUTPUT_ROOT="${DEFAULT_OUTPUT_ROOT:-build/clawmobile-runtime}"

TERMUX_APP_DATA_DIR="/data/user/0/${PACKAGE_NAME}"
TERMUX_HOME_DIR="${TERMUX_APP_DATA_DIR}/files/home"
TERMUX_PREFIX_DIR="${TERMUX_APP_DATA_DIR}/files/usr"
PROOT_ROOTFS_DIR="${TERMUX_PREFIX_DIR}/var/lib/proot-distro/installed-rootfs"
TERMUX_REL_HOME_DIR="./files/home"
TERMUX_REL_PREFIX_DIR="./files/usr"
PROOT_REL_ROOTFS_DIR="${TERMUX_REL_PREFIX_DIR}/var/lib/proot-distro/installed-rootfs"
REPO_DIR_NAME="ClawMobile"

ROOTFS_EXCLUDES=(
  "__ROOTFS__/apex"
  "__ROOTFS__/data"
  "__ROOTFS__/dev"
  "__ROOTFS__/linkerconfig"
  "__ROOTFS__/odm"
  "__ROOTFS__/proc"
  "__ROOTFS__/product"
  "__ROOTFS__/run"
  "__ROOTFS__/sys"
  "__ROOTFS__/system"
  "__ROOTFS__/system_ext"
  "__ROOTFS__/tmp"
  "__ROOTFS__/vendor"
  "__ROOTFS__/var/cache/apt/archives"
  "__ROOTFS__/var/lib/apt/lists"
  "__ROOTFS__/var/log"
  "__ROOTFS__/var/tmp"
  "__ROOTFS__/root/.cache"
)

TERMUX_PREFIX_EXCLUDES=(
  "usr/tmp"
  "usr/var/cache/apt/archives"
  "usr/var/lib/apt/lists"
  "usr/var/lib/proot-distro/dlcache"
  "usr/var/lib/proot-distro/installed-rootfs"
  "usr/var/log"
)

usage() {
  cat <<'EOF'
Usage:
  scripts/clawmobile-harvest-runtime.sh inspect [output_dir]
  scripts/clawmobile-harvest-runtime.sh export-repo [output_dir]
  scripts/clawmobile-harvest-runtime.sh export-termux-layer [output_dir]
  scripts/clawmobile-harvest-runtime.sh export-openclaw [output_dir]
  scripts/clawmobile-harvest-runtime.sh export-rootfs [output_dir]
  scripts/clawmobile-harvest-runtime.sh export-all [output_dir]

Environment:
  PACKAGE_NAME          Android package name to inspect (default: com.termux)
  ANDROID_SERIAL        adb serial override
  DEFAULT_OUTPUT_ROOT   Default output root (default: build/clawmobile-runtime)

Notes:
  - The script reads the installed Termux app data via `adb shell run-as`.
  - `inspect` writes manifests and package lists without modifying the device.
  - `export-termux-layer` exports the Termux-side package/runtime layer without the Ubuntu rootfs.
  - `export-rootfs` uses exclusion rules to avoid bundling obvious cache and bind-mounted paths.
EOF
}

ADB=(adb)
if [[ -n "${ANDROID_SERIAL}" ]]; then
  ADB+=(-s "${ANDROID_SERIAL}")
fi

log() {
  printf '[harvest] %s\n' "$*"
}

die() {
  printf '[harvest] ERROR: %s\n' "$*" >&2
  exit 1
}

adb_cmd() {
  "${ADB[@]}" "$@"
}

run_as_direct() {
  adb_cmd shell run-as "${PACKAGE_NAME}" "$@"
}

run_as_exec_out_direct() {
  adb_cmd exec-out run-as "${PACKAGE_NAME}" "$@"
}

run_as_shell() {
  local command="$1"
  adb_cmd shell run-as "${PACKAGE_NAME}" /system/bin/sh -c "${command}"
}

prefix_exclude_args() {
  local pattern
  for pattern in "${TERMUX_PREFIX_EXCLUDES[@]}"; do
    printf '%s\0' "--exclude" "${pattern}"
  done
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

timestamp() {
  date +"%Y%m%d-%H%M%S"
}

make_output_dir() {
  local requested="${1:-}"
  local dir
  if [[ -n "${requested}" ]]; then
    dir="${requested}"
  else
    dir="${DEFAULT_OUTPUT_ROOT}/$(timestamp)"
  fi

  mkdir -p "${dir}"
  printf '%s\n' "${dir}"
}

detect_rootfs_name() {
  local rootfs_names
  rootfs_names="$(run_as_direct /system/bin/ls -1 "${PROOT_REL_ROOTFS_DIR}" 2>/dev/null || true)"

  if grep -qx 'clawmobile-ubuntu' <<<"${rootfs_names}"; then
    printf 'clawmobile-ubuntu\n'
    return 0
  fi

  if grep -qx 'ubuntu' <<<"${rootfs_names}"; then
    printf 'ubuntu\n'
    return 0
  fi

  printf '%s\n' "${rootfs_names}" | sed -n '1p'
}

write_termux_status() {
  local out_file="$1"
  run_as_exec_out_direct cat "${TERMUX_REL_PREFIX_DIR}/var/lib/dpkg/status" > "${out_file}"
}

write_ubuntu_status() {
  local rootfs_name="$1"
  local out_file="$2"
  run_as_exec_out_direct cat "${PROOT_REL_ROOTFS_DIR}/${rootfs_name}/var/lib/dpkg/status" > "${out_file}"
}

extract_package_versions() {
  local status_file="$1"
  awk '
    /^Package: / { pkg = $2 }
    /^Version: / && pkg != "" { print pkg "\t" $2; pkg = "" }
  ' "${status_file}" | sort -u
}

write_size_report() {
  local rootfs_name="$1"
  local out_file="$2"
  {
    printf 'path\tdu_kib\n'
    run_as_direct /system/bin/du -s "${TERMUX_REL_HOME_DIR}/${REPO_DIR_NAME}" 2>/dev/null || true
    run_as_direct /system/bin/du -s "${TERMUX_REL_PREFIX_DIR}" 2>/dev/null || true
    if [[ -n "${rootfs_name}" ]]; then
      run_as_direct /system/bin/du -s "${PROOT_REL_ROOTFS_DIR}/${rootfs_name}" 2>/dev/null || true
      run_as_direct /system/bin/du -s "${PROOT_REL_ROOTFS_DIR}/${rootfs_name}/usr/lib/node_modules/openclaw" 2>/dev/null || true
    fi
  } | awk '
    NR == 1 { print; next }
    NF >= 2 { print $2 "\t" $1 }
  ' > "${out_file}"
}

write_tree_snapshots() {
  local rootfs_name="$1"
  local out_dir="$2"

  run_as_direct /system/bin/find "${TERMUX_REL_HOME_DIR}/${REPO_DIR_NAME}" -maxdepth 2 -mindepth 1 2>/dev/null \
    | sort > "${out_dir}/clawmobile-repo-tree.txt" || true

  if [[ -n "${rootfs_name}" ]]; then
    run_as_direct /system/bin/find "${PROOT_REL_ROOTFS_DIR}/${rootfs_name}/usr/lib/node_modules/openclaw" -maxdepth 2 -mindepth 1 2>/dev/null \
      | sort | head -n 200 > "${out_dir}/openclaw-tree.txt" || true
  fi
}

write_summary() {
  local rootfs_name="$1"
  local out_dir="$2"
  local openclaw_version="missing"
  local node_present="no"
  local npm_present="no"
  local adb_present="no"
  local openclaw_package_json

  if [[ -n "${rootfs_name}" ]]; then
    openclaw_package_json="$(
      run_as_exec_out_direct cat "${PROOT_REL_ROOTFS_DIR}/${rootfs_name}/usr/lib/node_modules/openclaw/package.json" 2>/dev/null || true
    )"
    openclaw_version="$(printf '%s\n' "${openclaw_package_json}" | sed -n 's/.*"version": "\([^"]*\)".*/\1/p' | head -n 1)"
    run_as_direct /system/bin/test -e "${PROOT_REL_ROOTFS_DIR}/${rootfs_name}/usr/bin/node" >/dev/null 2>&1 && node_present="yes"
    run_as_direct /system/bin/test -e "${PROOT_REL_ROOTFS_DIR}/${rootfs_name}/usr/bin/npm" >/dev/null 2>&1 && npm_present="yes"
    run_as_direct /system/bin/test -e "${PROOT_REL_ROOTFS_DIR}/${rootfs_name}/usr/bin/adb" >/dev/null 2>&1 && adb_present="yes"
  fi

  {
    printf 'package_name=%s\n' "${PACKAGE_NAME}"
    printf 'android_serial=%s\n' "${ANDROID_SERIAL:-<default>}"
    printf 'termux_home=%s\n' "${TERMUX_HOME_DIR}"
    printf 'termux_prefix=%s\n' "${TERMUX_PREFIX_DIR}"
    printf 'repo_dir=%s\n' "${TERMUX_HOME_DIR}/${REPO_DIR_NAME}"
    printf 'rootfs_name=%s\n' "${rootfs_name:-<none>}"
    printf 'rootfs_dir=%s\n' "${PROOT_ROOTFS_DIR}/${rootfs_name:-}"
    printf 'openclaw_version=%s\n' "${openclaw_version:-missing}"
    printf 'node_present=%s\n' "${node_present}"
    printf 'npm_present=%s\n' "${npm_present}"
    printf 'adb_present=%s\n' "${adb_present}"
  } > "${out_dir}/summary.txt"
}

inspect_runtime() {
  local out_dir="$1"
  local rootfs_name

  rootfs_name="$(detect_rootfs_name)"
  log "writing runtime inspection to ${out_dir}"

  write_termux_status "${out_dir}/termux-dpkg-status.txt"
  extract_package_versions "${out_dir}/termux-dpkg-status.txt" > "${out_dir}/termux-packages.txt"

  if [[ -n "${rootfs_name}" ]]; then
    write_ubuntu_status "${rootfs_name}" "${out_dir}/ubuntu-dpkg-status.txt"
    extract_package_versions "${out_dir}/ubuntu-dpkg-status.txt" > "${out_dir}/ubuntu-packages.txt"
  fi

  write_size_report "${rootfs_name}" "${out_dir}/sizes.tsv"
  write_tree_snapshots "${rootfs_name}" "${out_dir}"
  write_summary "${rootfs_name}" "${out_dir}"
}

export_tar_from_dir() {
  local out_file="$1"
  local base_dir="$2"
  shift 2

  log "exporting ${out_file}"
  run_as_exec_out_direct /system/bin/tar -C "${base_dir}" -cf - "$@" > "${out_file}"
}

export_repo() {
  local out_dir="$1"
  log "exporting ${out_dir}/clawmobile-repo.tar"
  run_as_exec_out_direct /system/bin/tar \
    -C "${TERMUX_REL_HOME_DIR}" \
    -cf - \
    --exclude "${REPO_DIR_NAME}/.git" \
    "${REPO_DIR_NAME}" > "${out_dir}/clawmobile-repo.tar"
}

export_termux_layer() {
  local out_dir="$1"
  local args=(-C "./files" -cf -)

  while IFS= read -r -d '' item; do
    args+=("${item}")
  done < <(prefix_exclude_args)
  args+=("usr")

  log "exporting ${out_dir}/termux-prefix-layer.tar"
  run_as_exec_out_direct /system/bin/tar "${args[@]}" > "${out_dir}/termux-prefix-layer.tar"
}

export_openclaw_runtime() {
  local out_dir="$1"
  local rootfs_name="$2"
  local include_paths=()
  local path

  [[ -n "${rootfs_name}" ]] || die "no Ubuntu rootfs detected"

  for path in \
    "${rootfs_name}/usr/bin/adb" \
    "${rootfs_name}/usr/bin/node" \
    "${rootfs_name}/usr/bin/npm" \
    "${rootfs_name}/usr/bin/openclaw" \
    "${rootfs_name}/usr/lib/node_modules/openclaw" \
    "${rootfs_name}/root/.openclaw"
  do
    if run_as_direct /system/bin/test -e "${PROOT_REL_ROOTFS_DIR}/${path}" >/dev/null 2>&1; then
      include_paths+=("${path}")
    fi
  done

  [[ "${#include_paths[@]}" -gt 0 ]] || die "no OpenClaw runtime paths found in ${rootfs_name}"

  export_tar_from_dir "${out_dir}/openclaw-runtime.tar" "${PROOT_REL_ROOTFS_DIR}" "${include_paths[@]}"
}

rootfs_exclude_args() {
  local rootfs_name="$1"
  local pattern
  for pattern in "${ROOTFS_EXCLUDES[@]}"; do
    printf '%s\0' "--exclude" "${pattern/__ROOTFS__/${rootfs_name}}"
  done
}

export_rootfs() {
  local out_dir="$1"
  local rootfs_name="$2"
  local args=(-C "${PROOT_ROOTFS_DIR}" -cf -)

  [[ -n "${rootfs_name}" ]] || die "no Ubuntu rootfs detected"

  while IFS= read -r -d '' item; do
    args+=("${item}")
  done < <(rootfs_exclude_args "${rootfs_name}")
  args+=("${rootfs_name}")

  log "exporting ${out_dir}/ubuntu-rootfs-harvested.tar"
  run_as_exec_out_direct /system/bin/tar "${args[@]}" > "${out_dir}/ubuntu-rootfs-harvested.tar"
}

main() {
  local command="${1:-}"
  local out_dir
  local rootfs_name

  case "${command}" in
    inspect|export-repo|export-termux-layer|export-openclaw|export-rootfs|export-all) ;;
    ""|-h|--help) usage; exit 0 ;;
    *) die "unknown command: ${command}" ;;
  esac

  shift || true

  require_device
  require_run_as

  out_dir="$(make_output_dir "${1:-}")"
  rootfs_name="$(detect_rootfs_name)"

  case "${command}" in
    inspect)
      inspect_runtime "${out_dir}"
      ;;
    export-repo)
      inspect_runtime "${out_dir}"
      export_repo "${out_dir}"
      ;;
    export-termux-layer)
      inspect_runtime "${out_dir}"
      export_termux_layer "${out_dir}"
      ;;
    export-openclaw)
      inspect_runtime "${out_dir}"
      export_openclaw_runtime "${out_dir}" "${rootfs_name}"
      ;;
    export-rootfs)
      inspect_runtime "${out_dir}"
      export_rootfs "${out_dir}" "${rootfs_name}"
      ;;
    export-all)
      inspect_runtime "${out_dir}"
      export_repo "${out_dir}"
      export_termux_layer "${out_dir}"
      export_openclaw_runtime "${out_dir}" "${rootfs_name}"
      export_rootfs "${out_dir}" "${rootfs_name}"
      ;;
  esac

  log "done: ${out_dir}"
}

main "$@"
