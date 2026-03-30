#!/usr/bin/env bash
set -euo pipefail

echo "[+] Updating apt..."
apt update -y

echo "[+] Installing base dependencies..."
# python3-venv/python3-pip: Debian/Ubuntu 正确 pip 方式（避免 ensurepip 缺失问题）
# nodejs/npm: OpenClaw/插件常用
apt install -y \
  android-tools-adb \
  python3 python3-venv python3-pip \
  curl rsync

echo "[+] Creating venv for clawbot/openclaw tooling..."
mkdir -p /root/venvs
if [[ ! -d /root/venvs/clawbot ]]; then
  python3 -m venv /root/venvs/clawbot
fi

# Activate venv
# shellcheck disable=SC1091
source /root/venvs/clawbot/bin/activate

echo "[+] Upgrading pip toolchain in venv..."
python -m pip install --upgrade pip

echo "[+] Installing Droidrun runtime (pip, no uv)..."
# Install the Python package during bootstrap, but do not run the Portal/device
# setup here. That step needs working adb connectivity plus user-granted
# Android permissions, so it is handled separately after Wireless ADB is linked.
python -m pip install "droidrun[google,anthropic,openai,deepseek,ollama,openrouter]"

echo "[+] Verifying droidrun import..."
python - <<'PY'
import droidrun
print(getattr(droidrun, "__version__", "ok"))
PY

echo "[*] Droidrun package installed."
echo "[*] Portal setup is deferred until local adb is linked."
echo "[*] Next step after Wireless ADB connect:"
echo "    ./installer/termux/droidrun-setup.sh"


# Apply env hardening (cache/tmp inside venv)
# Assumes this script runs from repo root OR you can adjust path
if [[ -f "installer/ubuntu/env.sh" ]]; then
  # shellcheck disable=SC1091
  source "installer/ubuntu/env.sh"
else
  echo "[!] installer/ubuntu/env.sh not found in current directory."
  echo "    Please run bootstrap.sh from the repo root."
  exit 1
fi

echo
echo "[*] OpenClaw installation"
echo

curl -fsSL https://openclaw.ai/install.sh | bash -s -- --no-onboard


echo "[✓] Bootstrap complete."
