#!/usr/bin/env bash
set -euo pipefail

SERVICE_NAME="hamster-wheel-tracker"
STATE_DIR="/var/lib/${SERVICE_NAME}"
ENV_FILE="/etc/default/${SERVICE_NAME}"
UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
SERVICE_USER="${SUDO_USER:-}"

usage() {
  cat <<EOF
Usage: sudo bash deploy/install_service.sh [--user USER]

Installs/updates the Python environment and systemd service using the current
repository checkout at:
  ${REPO_DIR}

Override the interpreter when needed with, for example:
  sudo env PYTHON_BIN=/usr/local/bin/python3.8 bash deploy/install_service.sh --user USER
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --user)
      SERVICE_USER="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ ${EUID} -ne 0 ]]; then
  echo "This installer writes systemd and /var/lib state. Run it with sudo." >&2
  exit 2
fi

if [[ -z "${SERVICE_USER}" || "${SERVICE_USER}" == "root" ]]; then
  echo "Pass the normal login user explicitly, e.g. --user jetson" >&2
  exit 2
fi

if ! id "${SERVICE_USER}" >/dev/null 2>&1; then
  echo "User does not exist: ${SERVICE_USER}" >&2
  exit 2
fi

SERVICE_GROUP="$(id -gn "${SERVICE_USER}")"
VENV_DIR="${REPO_DIR}/.venv"
PYTHON_BIN="${PYTHON_BIN:-python3}"

if [[ ! -f "${REPO_DIR}/pyproject.toml" ]]; then
  echo "pyproject.toml not found in ${REPO_DIR}" >&2
  exit 2
fi

if ! command -v "${PYTHON_BIN}" >/dev/null 2>&1 && [[ ! -x "${PYTHON_BIN}" ]]; then
  echo "Python interpreter not found: ${PYTHON_BIN}" >&2
  exit 3
fi

if ! "${PYTHON_BIN}" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 8) else 1)'; then
  VERSION="$(${PYTHON_BIN} --version 2>&1 || true)"
  cat >&2 <<EOF
Unsupported Python runtime: ${VERSION}

hamster-wheel-tracker currently requires Python >= 3.8.
Stock JetPack 4.x / Ubuntu 18.04 commonly uses Python 3.6, so do not replace
/usr/bin/python3 just to satisfy this installer; NVIDIA camera/OpenCV bindings may
depend on the system Python.

Use a separate Python >= 3.8 interpreter and re-run with PYTHON_BIN=/path/to/python,
or wait until the Jetson hardware is available so its camera/Python stack can be
validated before choosing the compatibility strategy.
EOF
  exit 3
fi

install -d -m 0755 -o "${SERVICE_USER}" -g "${SERVICE_GROUP}" "${STATE_DIR}"

if [[ ! -x "${VENV_DIR}/bin/python" ]]; then
  echo "Creating virtual environment: ${VENV_DIR}"
  # Jetson images often provide OpenCV through the system Python, so preserve
  # access to system site packages instead of forcing a pip OpenCV wheel.
  runuser -u "${SERVICE_USER}" -- "${PYTHON_BIN}" -m venv --system-site-packages "${VENV_DIR}"
fi

echo "Installing/updating hamster-wheel-tracker"
runuser -u "${SERVICE_USER}" -- "${VENV_DIR}/bin/python" -m pip install -e "${REPO_DIR}"

if [[ ! -f "${STATE_DIR}/config.json" ]]; then
  echo "Creating initial persistent configuration"
  runuser -u "${SERVICE_USER}" -- env \
    HWT_CONFIG="${STATE_DIR}/config.json" \
    HWT_DATABASE="${STATE_DIR}/tracker.db" \
    "${VENV_DIR}/bin/python" -c \
    'import os; from hamster_tracker.config import AppConfig; c=AppConfig(); c.storage.database_path=os.environ["HWT_DATABASE"]; c.save(os.environ["HWT_CONFIG"])'
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  cat >"${ENV_FILE}" <<EOF
HAMSTER_TRACKER_CONFIG=${STATE_DIR}/config.json
HAMSTER_TRACKER_DB=${STATE_DIR}/tracker.db
HAMSTER_TRACKER_HOST=0.0.0.0
HAMSTER_TRACKER_PORT=8000
HAMSTER_TRACKER_LOG_LEVEL=info
EOF
  chmod 0644 "${ENV_FILE}"
  echo "Created ${ENV_FILE}"
else
  echo "Keeping existing ${ENV_FILE}"
fi

TEMPLATE="${SCRIPT_DIR}/hamster-wheel-tracker.service.in"
export TEMPLATE UNIT_FILE SERVICE_USER SERVICE_GROUP REPO_DIR VENV_DIR
"${PYTHON_BIN}" - <<'PY'
import os
from pathlib import Path

text = Path(os.environ["TEMPLATE"]).read_text(encoding="utf-8")
replacements = {
    "@SERVICE_USER@": os.environ["SERVICE_USER"],
    "@SERVICE_GROUP@": os.environ["SERVICE_GROUP"],
    "@REPO_DIR@": os.environ["REPO_DIR"],
    "@VENV_DIR@": os.environ["VENV_DIR"],
}
for key, value in replacements.items():
    text = text.replace(key, value)
Path(os.environ["UNIT_FILE"]).write_text(text, encoding="utf-8")
PY
chmod 0644 "${UNIT_FILE}"

systemctl daemon-reload
systemctl enable --now "${SERVICE_NAME}.service"

echo
echo "Installed ${SERVICE_NAME}.service"
echo "Dashboard: http://<jetson-ip>:8000"
echo "Status:    systemctl status ${SERVICE_NAME} --no-pager"
echo "Logs:      journalctl -u ${SERVICE_NAME} -f"
echo "Doctor:    ${VENV_DIR}/bin/hamster-tracker doctor"
