#!/data/data/ae.clawmobile/files/usr/bin/bash
set -euo pipefail

UBUNTU_DISTRO="${UBUNTU_DISTRO:-ubuntu}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "[droidrun-setup] Starting adb server..."
adb start-server >/dev/null 2>&1 || true

if ! adb devices | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'; then
  echo "[droidrun-setup] ERROR: no adb device in 'device' state."
  echo "[droidrun-setup] Pair and connect the local device first from the Setup tab."
  adb devices || true
  exit 1
fi

echo "[droidrun-setup] Entering Ubuntu and completing Droidrun portal setup..."
proot-distro login "${UBUNTU_DISTRO}" --shared-tmp -- \
  bash -lc "
    set -euo pipefail
    cd '${REPO_ROOT}'

    if [ -f '/root/venvs/clawbot/bin/activate' ]; then
      # shellcheck disable=SC1091
      source '/root/venvs/clawbot/bin/activate'
    fi

    echo '[droidrun-setup] adb devices inside Ubuntu:'
    adb devices || true

    echo '[droidrun-setup] Running droidrun setup...'
    droidrun setup

    echo '[droidrun-setup] Verifying portal with droidrun ping...'
    droidrun ping

    echo '[droidrun-setup] Droidrun portal is ready.'
  "
