from datetime import datetime, timedelta
import os
import time

from fastapi import FastAPI
from fastapi.responses import HTMLResponse

from hamster_tracker.storage.database import Database


DASHBOARD_HTML = """<!doctype html>
<html>
<head>
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Hamster Wheel Tracker</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 720px; margin: 0 auto; padding: 24px; background: #f6f6f6; }
    .card { background: white; padding: 18px; border-radius: 14px; margin-bottom: 12px; }
    .value { font-size: 2rem; font-weight: 700; }
    .muted { color: #666; }
  </style>
</head>
<body>
  <h1>Hamster Wheel Tracker</h1>
  <div class="card"><div class="muted">Status</div><div class="value">Core ready</div></div>
  <div class="card">Camera integration and calibration preview will be enabled when Jetson hardware is available.</div>
  <div class="card"><pre id="summary">Loading...</pre></div>
  <script>
    fetch('/api/today').then(r => r.json()).then(x => {
      document.getElementById('summary').textContent = JSON.stringify(x, null, 2);
    });
  </script>
</body>
</html>"""


def _local_day_bounds(now_ts: float):
    now = datetime.fromtimestamp(now_ts)
    start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    end = start + timedelta(days=1)
    return start.timestamp(), end.timestamp()


def create_app(database_path: str = None) -> FastAPI:
    app = FastAPI(title="Hamster Wheel Tracker", version="0.1.0")
    db_path = database_path or os.environ.get("HAMSTER_TRACKER_DB", "data/tracker.db")
    db = Database(db_path)

    @app.on_event("shutdown")
    def close_database():
        db.close()

    @app.get("/", response_class=HTMLResponse)
    def dashboard():
        return DASHBOARD_HTML

    @app.get("/api/health")
    def health():
        return {"ok": True, "camera": "not_configured"}

    @app.get("/api/today")
    def today():
        start_ts, end_ts = _local_day_bounds(time.time())
        return db.summary(start_ts, end_ts)

    @app.get("/api/sessions")
    def sessions():
        start_ts, end_ts = _local_day_bounds(time.time())
        return db.sessions(start_ts, end_ts)

    return app


app = create_app()
