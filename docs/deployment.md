# Jetson Runtime and Deployment

> **Roadmap status:** Android is now the primary product/deployment target. This document is preserved for the optional Linux/Jetson backend and for the runtime/deployment work already implemented in PR #12. Jetson hardware validation is deferred and does not block the Android roadmap.

This document covers the persistent service layer. Camera bring-up is intentionally separate so the service can already be developed and tested without Jetson hardware.

## Jetson Nano compatibility note

The original Jetson Nano is officially capped at the JetPack 4 series. NVIDIA's final Nano release is JetPack 4.6.6 / Jetson Linux R32.7.6, whose root filesystem is based on Ubuntu 18.04. Ubuntu 18.04 uses Python 3.6 as its default Python 3 runtime.

The current project intentionally still declares Python >= 3.8. Therefore **the stock JetPack 4 Python is not yet a supported deployment target**.

Do not replace `/usr/bin/python3` on a Jetson simply to satisfy this project. NVIDIA camera/OpenCV/GStreamer packages can depend on the system Python ABI. The installer checks the selected interpreter and stops before changing the system when it is too old.

If Jetson support is revisited, first record:

```bash
python3 --version
cat /etc/nv_tegra_release
python3 -c 'import cv2; print(cv2.__version__)'
```

Then choose one of two strategies based on the actual image and camera stack:

1. add a deliberately pinned Python 3.6 compatibility profile for JetPack 4, or
2. use a separate Python >= 3.8 interpreter while proving that the CSI/GStreamer capture path still works correctly.

Until that hardware check is done, the runtime/service code remains useful and fully testable on modern Python, but the installer will fail closed on Python < 3.8 rather than risking the Jetson system environment.

## Runtime layout

The recommended installed layout keeps source code and persistent state separate:

```text
<git checkout>/
  .venv/                       Python environment
  src/                         application source
  deploy/                      systemd installer/template

/var/lib/hamster-wheel-tracker/
  config.json                  calibration + tracker configuration
  tracker.db                   SQLite history

/etc/default/hamster-wheel-tracker
                               host/port/path environment overrides
```

The calibration page writes `config.json`, so it belongs in writable persistent state rather than a read-only package/configuration directory.

## Command line runtime

Installing the Python project exposes a `hamster-tracker` command.

Validate persistent state without camera hardware:

```bash
hamster-tracker doctor \
  --config /tmp/hamster-test/config.json \
  --database /tmp/hamster-test/tracker.db
```

Run the web service manually:

```bash
hamster-tracker serve \
  --config ./config.json \
  --database ./data/tracker.db \
  --host 0.0.0.0 \
  --port 8000
```

CLI values take precedence over environment variables, which take precedence over config/default values.

Supported environment variables:

```text
HAMSTER_TRACKER_CONFIG
HAMSTER_TRACKER_DB
HAMSTER_TRACKER_HOST
HAMSTER_TRACKER_PORT
HAMSTER_TRACKER_LOG_LEVEL
```

Relative database paths from `config.json` are resolved relative to the config file rather than the process working directory. This prevents a systemd launch from silently writing to a different database than a manual launch.

## Systemd installation

On a compatible Python >= 3.8 system, clone the repository as the normal login user and enter the checkout. Then run:

```bash
sudo bash deploy/install_service.sh --user "$USER"
```

To select a non-default interpreter explicitly:

```bash
sudo env PYTHON_BIN=/path/to/python3.8 \
  bash deploy/install_service.sh --user "$USER"
```

The installer:

1. validates that the selected Python is >= 3.8,
2. creates `/var/lib/hamster-wheel-tracker` owned by the selected user,
3. creates `.venv` with `--system-site-packages`,
4. installs the project in editable mode,
5. creates a persistent initial config if one does not already exist,
6. creates `/etc/default/hamster-wheel-tracker` on first install,
7. renders `/etc/systemd/system/hamster-wheel-tracker.service`, and
8. enables and starts the service.

`--system-site-packages` is deliberate: Jetson images commonly provide hardware-integrated packages such as OpenCV through the system Python. The installer does not force-install the optional `vision` extra.

The installer preserves an existing config, database, and `/etc/default/hamster-wheel-tracker` on repeat runs.

## Service behavior

The unit uses:

```text
Restart=always
RestartSec=3
KillSignal=SIGINT
TimeoutStopSec=20
```

A crash therefore restarts automatically. A normal `systemctl stop` remains stopped because systemd suppresses restart for an explicit administrator stop. SIGINT allows Uvicorn/FastAPI shutdown handlers to run before systemd applies a hard kill.

Useful commands:

```bash
sudo systemctl status hamster-wheel-tracker --no-pager
sudo systemctl restart hamster-wheel-tracker
sudo systemctl stop hamster-wheel-tracker
sudo systemctl start hamster-wheel-tracker
journalctl -u hamster-wheel-tracker -f
```

After a reboot:

```bash
systemctl is-enabled hamster-wheel-tracker
systemctl is-active hamster-wheel-tracker
```

Both should report the expected enabled/active state once a compatible Linux/Jetson deployment has been validated on real hardware.

## Dashboard and health

With the default environment, the service binds to:

```text
0.0.0.0:8000
```

From a phone on the same trusted LAN, open:

```text
http://<host-ip>:8000
```

Basic process/API health:

```bash
curl http://127.0.0.1:8000/api/health
curl http://127.0.0.1:8000/api/status
```

Without camera integration, the web service intentionally starts in a degraded hardware-independent state and reports that the camera is not configured. This keeps the Python configuration, history, dashboard, and deployment path useful as reference/debug tooling.

## Updating code

From the checkout:

```bash
git pull
sudo bash deploy/install_service.sh --user "$USER"
```

The repeat install updates the editable Python environment and service definition while preserving `/var/lib/hamster-wheel-tracker`.

For a code-only change that does not alter dependencies or the unit file, a restart is enough:

```bash
git pull
sudo systemctl restart hamster-wheel-tracker
```

## Persistence and backup

The two files worth backing up are:

```text
/var/lib/hamster-wheel-tracker/config.json
/var/lib/hamster-wheel-tracker/tracker.db
```

Stopping the service before copying the SQLite database is the simplest safe manual backup procedure:

```bash
sudo systemctl stop hamster-wheel-tracker
sudo cp /var/lib/hamster-wheel-tracker/tracker.db /path/to/backup/
sudo systemctl start hamster-wheel-tracker
```

## Network note

The reference web backend has no authentication layer. Binding to `0.0.0.0` is intended for a trusted home LAN only. Do not expose port 8000 directly to the public internet.

## Deferred hardware validation

These checks are intentionally deferred while Android is the primary roadmap:

- choose/validate the Python strategy for the actual JetPack image
- service starts after a physical reboot
- CSI camera worker can access the camera under the systemd user
- low-light capture remains stable overnight
- dashboard is reachable from the real LAN
- restart/recovery works after deliberately killing the tracker process

The old Jetson-specific issues were closed as `not planned`, not because this runtime code is invalid, but because those checks no longer block the product.
