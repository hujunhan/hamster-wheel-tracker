# Jetson Runtime and Deployment

This document covers the persistent service layer. Camera bring-up is intentionally separate so the service can already be developed and tested without Jetson hardware.

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

On the Jetson, clone the repository as the normal login user and enter the checkout. Then run:

```bash
sudo bash deploy/install_service.sh --user "$USER"
```

The installer:

1. creates `/var/lib/hamster-wheel-tracker` owned by the selected user,
2. creates `.venv` with `--system-site-packages`,
3. installs the project in editable mode,
4. creates a persistent initial config if one does not already exist,
5. creates `/etc/default/hamster-wheel-tracker` on first install,
6. renders `/etc/systemd/system/hamster-wheel-tracker.service`, and
7. enables and starts the service.

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

Both should report the expected enabled/active state once the Jetson deployment has been validated on real hardware.

## Dashboard and health

With the default environment, the service binds to:

```text
0.0.0.0:8000
```

From a phone on the same trusted LAN, open:

```text
http://<jetson-ip>:8000
```

Basic process/API health:

```bash
curl http://127.0.0.1:8000/api/health
curl http://127.0.0.1:8000/api/status
```

Until camera integration is implemented, the web service intentionally starts in a degraded hardware-independent state and reports that the camera is not configured. This lets configuration, history, dashboard, and deployment remain testable.

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

The MVP has no authentication layer. Binding to `0.0.0.0` is intended for a trusted home LAN only. Do not expose port 8000 directly to the public internet. Remote access should later be placed behind a VPN or authenticated reverse proxy if needed.

## Hardware validation still required

These deployment pieces are testable in CI, but the following acceptance checks require the actual Jetson Nano:

- service starts after a physical reboot,
- Jetson's installed Python can create/use the venv,
- the eventual CSI camera worker can access the camera under the systemd user,
- low-light capture remains stable overnight,
- dashboard is reachable from the phone on the real LAN,
- restart/recovery behavior works after deliberately killing the tracker process.
