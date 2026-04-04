#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

UBUNTU="clawmobile-ubuntu"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
CUSTOM_PLUGIN="${PREFIX}/etc/proot-distro/${UBUNTU}.sh"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CUSTOM_PLUGIN_SOURCE="${SCRIPT_DIR}/${UBUNTU}.sh"

install_custom_ubuntu_plugin() {
  if [ ! -f "${CUSTOM_PLUGIN_SOURCE}" ]; then
    echo "[install] ERROR: bundled ClawMobile Ubuntu plugin not found at ${CUSTOM_PLUGIN_SOURCE}" >&2
    exit 1
  fi
  install -m 0644 "${CUSTOM_PLUGIN_SOURCE}" "${CUSTOM_PLUGIN}"
}

echo "[+] Updating Termux packages..."
pkg update -y
pkg upgrade -y

echo "[+] Installing prerequisites..."
pkg install -y proot-distro git curl termux-api android-tools

echo "[+] Installing ClawMobile Ubuntu plugin..."
install_custom_ubuntu_plugin

echo "[+] Installing proot Ubuntu (${UBUNTU}) if missing..."
UBUNTU_ROOTFS="${PREFIX}/var/lib/proot-distro/installed-rootfs/${UBUNTU}"
if [ ! -d "$UBUNTU_ROOTFS" ]; then
  echo "[install] Ubuntu not found, installing..."
  proot-distro install "$UBUNTU"
else
  echo "[install] Ubuntu already installed, skipping"
fi

echo "[+] Entering Ubuntu and running bootstrap..."

# Launch Ubuntu and run bootstrap inside it
proot-distro login "${UBUNTU}" --shared-tmp -- \
  bash -lc "cd '${REPO_ROOT}' && chmod +x installer/ubuntu/*.sh && ./installer/ubuntu/bootstrap.sh"

echo

echo
echo "[✓] Install finished."
echo
echo "Back in Termux, run the next steps from the project root:"
echo "  1) Run onboarding (interactive):"
echo "     ./installer/termux/onboard.sh"
echo
echo "  2) After wireless ADB pairing, finish DroidRun setup:"
echo "     ./installer/termux/droidrun-setup.sh"
echo
echo "  3) Start gateway anytime:"
echo "     ./installer/termux/run.sh"
