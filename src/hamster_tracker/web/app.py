from datetime import date, datetime, time as datetime_time, timedelta
import os
import time
from typing import Dict, List, Optional, Tuple

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import HTMLResponse

from hamster_tracker.storage.database import Database


NIGHT_ROLLOVER_HOUR = 18


DASHBOARD_HTML = r"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <meta name="theme-color" content="#111315">
  <title>Hamster Wheel Tracker</title>
  <style>
    :root {
      color-scheme: light dark;
      --bg: #f3f4f6;
      --surface: #ffffff;
      --surface-2: #f7f7f8;
      --text: #151719;
      --muted: #6b7076;
      --border: #e3e5e8;
      --accent: #4e7c5b;
      --accent-soft: #dfece2;
      --warn: #b36b24;
      --warn-soft: #f5e6d6;
    }
    @media (prefers-color-scheme: dark) {
      :root {
        --bg: #0f1113;
        --surface: #191c1f;
        --surface-2: #202428;
        --text: #f4f5f6;
        --muted: #a2a8ae;
        --border: #2b3035;
        --accent: #7fbc91;
        --accent-soft: #20372a;
        --warn: #e0a15f;
        --warn-soft: #432f1d;
      }
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      background: var(--bg);
      color: var(--text);
      font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    main { max-width: 860px; margin: 0 auto; padding: 22px 16px 48px; }
    header { display:flex; justify-content:space-between; gap:16px; align-items:flex-start; margin-bottom:18px; }
    h1 { font-size: 1.35rem; margin:0 0 4px; }
    h2 { font-size: 1rem; margin:0; }
    .subtitle, .muted { color: var(--muted); }
    .subtitle { font-size:.86rem; }
    .status {
      display:inline-flex; align-items:center; gap:7px; padding:7px 10px;
      border-radius:999px; background:var(--surface); border:1px solid var(--border);
      font-size:.8rem; white-space:nowrap;
    }
    .dot { width:8px; height:8px; border-radius:50%; background:var(--muted); }
    .status.running .dot { background:var(--accent); }
    .status.uncertain .dot { background:var(--warn); }
    .hero {
      background:var(--surface); border:1px solid var(--border); border-radius:20px;
      padding:22px; margin-bottom:12px;
    }
    .hero-label { color:var(--muted); font-size:.82rem; }
    .hero-value { font-size:clamp(2.6rem, 11vw, 4.8rem); font-weight:760; letter-spacing:-.06em; line-height:1; margin:8px 0; }
    .hero-unit { font-size:1rem; color:var(--muted); font-weight:500; letter-spacing:0; }
    .hero-note { color:var(--muted); font-size:.83rem; }
    .metrics { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; margin-bottom:12px; }
    @media (min-width:680px) { .metrics { grid-template-columns:repeat(4,minmax(0,1fr)); } }
    .metric, .panel {
      background:var(--surface); border:1px solid var(--border); border-radius:16px;
    }
    .metric { padding:15px; min-height:94px; }
    .metric-label { color:var(--muted); font-size:.76rem; margin-bottom:8px; }
    .metric-value { font-size:1.35rem; font-weight:680; }
    .metric-unit { color:var(--muted); font-size:.75rem; margin-left:3px; }
    .panel { padding:16px; margin-bottom:12px; overflow:hidden; }
    .panel-head { display:flex; align-items:baseline; justify-content:space-between; gap:12px; margin-bottom:14px; }
    .panel-note { font-size:.75rem; color:var(--muted); }
    .hour-chart { height:150px; display:flex; gap:5px; align-items:flex-end; overflow-x:auto; padding:5px 1px 2px; }
    .hour { flex:1 0 24px; min-width:24px; height:100%; display:flex; flex-direction:column; justify-content:flex-end; align-items:center; gap:5px; }
    .hour-bar-wrap { height:112px; width:100%; display:flex; align-items:flex-end; }
    .hour-bar { width:100%; min-height:2px; border-radius:5px 5px 2px 2px; background:var(--accent); opacity:.88; }
    .hour-label { font-size:.62rem; color:var(--muted); }
    .timeline { width:100%; height:155px; display:block; overflow:visible; }
    .timeline .axis { stroke:var(--border); stroke-width:1; }
    .timeline .line { fill:none; stroke:var(--accent); stroke-width:3; stroke-linecap:round; stroke-linejoin:round; }
    .timeline .uncertain { fill:var(--warn-soft); }
    .empty { color:var(--muted); font-size:.85rem; padding:18px 0; }
    .session, .history-row { display:grid; align-items:center; gap:10px; border-top:1px solid var(--border); padding:12px 0; }
    .session:first-child, .history-row:first-child { border-top:0; }
    .session { grid-template-columns:1fr auto; }
    .history-row { grid-template-columns:1fr auto auto; }
    .row-main { font-weight:620; }
    .row-sub { font-size:.74rem; color:var(--muted); margin-top:3px; }
    .row-number { text-align:right; font-variant-numeric:tabular-nums; }
    .row-number strong { display:block; }
    .row-number span { font-size:.7rem; color:var(--muted); }
    .warning {
      display:none; background:var(--warn-soft); border-radius:12px; padding:11px 12px;
      font-size:.78rem; margin-bottom:12px; color:var(--warn);
    }
    .warning.visible { display:block; }
    footer { color:var(--muted); font-size:.72rem; text-align:center; padding:8px; }
    button {
      border:1px solid var(--border); background:var(--surface); color:var(--text);
      border-radius:10px; padding:7px 10px; font:inherit; font-size:.76rem; cursor:pointer;
    }
  </style>
</head>
<body>
<main>
  <header>
    <div>
      <h1>Hamster Wheel</h1>
      <div class="subtitle" id="nightLabel">Loading activity…</div>
    </div>
    <div class="status" id="status"><span class="dot"></span><span id="statusText">Loading</span></div>
  </header>

  <div class="warning" id="uncertainWarning"></div>

  <section class="hero">
    <div class="hero-label">Distance this night</div>
    <div class="hero-value"><span id="distance">—</span> <span class="hero-unit">km</span></div>
    <div class="hero-note" id="heroNote">Waiting for tracker data</div>
  </section>

  <section class="metrics">
    <div class="metric"><div class="metric-label">Running time</div><div class="metric-value" id="runningTime">—</div></div>
    <div class="metric"><div class="metric-label">Equivalent revolutions</div><div class="metric-value" id="revolutions">—</div></div>
    <div class="metric"><div class="metric-label">Average speed</div><div class="metric-value"><span id="avgSpeed">—</span><span class="metric-unit">km/h</span></div></div>
    <div class="metric"><div class="metric-label">Max speed</div><div class="metric-value"><span id="maxSpeed">—</span><span class="metric-unit">km/h</span></div></div>
    <div class="metric"><div class="metric-label">Longest session</div><div class="metric-value" id="longestSession">—</div></div>
    <div class="metric"><div class="metric-label">Sessions</div><div class="metric-value" id="sessionCount">—</div></div>
  </section>

  <section class="panel">
    <div class="panel-head"><h2>Distance by hour</h2><span class="panel-note">18:00 → 18:00</span></div>
    <div class="hour-chart" id="hourChart"></div>
  </section>

  <section class="panel">
    <div class="panel-head"><h2>Speed timeline</h2><span class="panel-note" id="timelineNote"></span></div>
    <div id="timeline"></div>
  </section>

  <section class="panel">
    <div class="panel-head"><h2>Sessions</h2><span class="panel-note" id="sessionNote"></span></div>
    <div id="sessions"></div>
  </section>

  <section class="panel">
    <div class="panel-head"><h2>Recent nights</h2><button onclick="loadDashboard()">Refresh</button></div>
    <div id="history"></div>
  </section>
  <footer>Local-only dashboard · data stored on the Jetson Nano</footer>
</main>
<script>
const fmt = new Intl.NumberFormat(undefined, {maximumFractionDigits: 2});
const fmt1 = new Intl.NumberFormat(undefined, {maximumFractionDigits: 1});

function duration(seconds) {
  seconds = Math.max(0, Number(seconds || 0));
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h) return `${h}h ${m}m`;
  if (m) return `${m}m ${s}s`;
  return `${s}s`;
}
function clock(ts) {
  return new Date(ts * 1000).toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
}
function shortDate(ts) {
  return new Date(ts * 1000).toLocaleDateString([], {month:'short', day:'numeric'});
}
function statusLabel(status) {
  if (!status || !status.available) return 'No data';
  if (status.stale) return 'Tracker offline';
  if (status.tracking_state === 'UNCERTAIN') return 'Tracking uncertain';
  if (status.running) return 'Running';
  if (status.tracking_state === 'PREDICTING') return 'Marker hidden';
  return 'Ready';
}
function renderStatus(status) {
  const el = document.getElementById('status');
  el.className = 'status';
  if (status && !status.stale && status.running) el.classList.add('running');
  if (status && status.tracking_state === 'UNCERTAIN') el.classList.add('uncertain');
  document.getElementById('statusText').textContent = statusLabel(status);
}
function renderHours(hours) {
  const root = document.getElementById('hourChart');
  root.innerHTML = '';
  const max = Math.max(0.001, ...hours.map(x => x.distance_m));
  hours.forEach((x, i) => {
    const col = document.createElement('div'); col.className = 'hour';
    const wrap = document.createElement('div'); wrap.className = 'hour-bar-wrap';
    const bar = document.createElement('div'); bar.className = 'hour-bar';
    bar.style.height = `${Math.max(x.distance_m > 0 ? 3 : 1, 100 * x.distance_m / max)}%`;
    bar.title = `${clock(x.start_ts)} · ${fmt.format(x.distance_m)} m`;
    wrap.appendChild(bar);
    const label = document.createElement('div'); label.className = 'hour-label';
    label.textContent = i % 3 === 0 ? new Date(x.start_ts * 1000).getHours().toString().padStart(2,'0') : '';
    col.append(wrap, label); root.appendChild(col);
  });
}
function renderTimeline(points) {
  const root = document.getElementById('timeline');
  if (!points.length) { root.innerHTML = '<div class="empty">No activity samples yet.</div>'; return; }
  const W=720, H=150, P=8;
  const maxSpeed = Math.max(0.01, ...points.map(x => x.speed_m_s));
  let path = '';
  points.forEach((x,i) => {
    const px = P + (W-2*P) * (points.length === 1 ? 0 : i/(points.length-1));
    const py = H-P - (H-2*P) * (x.speed_m_s/maxSpeed);
    path += `${i?'L':'M'}${px.toFixed(1)},${py.toFixed(1)} `;
  });
  let bands='';
  points.forEach((x,i) => {
    if (!x.uncertain) return;
    const bw=(W-2*P)/Math.max(1,points.length);
    const px=P+i*bw;
    bands += `<rect class="uncertain" x="${px.toFixed(1)}" y="${P}" width="${Math.max(2,bw).toFixed(1)}" height="${H-2*P}" rx="2"/>`;
  });
  root.innerHTML = `<svg class="timeline" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" aria-label="Speed timeline">${bands}<line class="axis" x1="${P}" y1="${H-P}" x2="${W-P}" y2="${H-P}"/><path class="line" d="${path}"/></svg>`;
  document.getElementById('timelineNote').textContent = `peak ${fmt1.format(maxSpeed*3.6)} km/h`;
}
function renderSessions(rows) {
  const root=document.getElementById('sessions'); root.innerHTML='';
  document.getElementById('sessionNote').textContent = `${rows.length} recorded`;
  if (!rows.length) { root.innerHTML='<div class="empty">No completed running sessions yet.</div>'; return; }
  rows.slice(0,8).forEach(x => {
    const el=document.createElement('div'); el.className='session';
    el.innerHTML=`<div><div class="row-main">${clock(x.start_ts)} – ${clock(x.end_ts)}</div><div class="row-sub">${duration(x.moving_duration_s)} moving · max ${fmt1.format(x.max_speed_m_s*3.6)} km/h</div></div><div class="row-number"><strong>${fmt.format(x.distance_m)} m</strong><span>${fmt1.format(x.equivalent_revolutions)} rev</span></div>`;
    root.appendChild(el);
  });
}
function renderHistory(rows) {
  const root=document.getElementById('history'); root.innerHTML='';
  rows.forEach(x => {
    const el=document.createElement('div'); el.className='history-row';
    el.innerHTML=`<div><div class="row-main">${shortDate(x.start_ts)}</div><div class="row-sub">${duration(x.moving_duration_s)} · ${Math.round(x.session_count)} sessions</div></div><div class="row-number"><strong>${fmt.format(x.distance_m/1000)} km</strong><span>distance</span></div><div class="row-number"><strong>${fmt1.format(x.max_speed_m_s*3.6)}</strong><span>km/h max</span></div>`;
    root.appendChild(el);
  });
}
async function loadDashboard() {
  try {
    const [data, history] = await Promise.all([
      fetch('/api/dashboard').then(r => r.json()),
      fetch('/api/history?days=7').then(r => r.json())
    ]);
    const s=data.summary;
    document.getElementById('nightLabel').textContent=`Night of ${shortDate(data.start_ts)} · ${clock(data.start_ts)}–${clock(data.end_ts)}`;
    document.getElementById('distance').textContent=(s.distance_m/1000).toFixed(s.distance_m >= 1000 ? 2 : 3);
    document.getElementById('runningTime').textContent=duration(s.moving_duration_s);
    document.getElementById('revolutions').textContent=fmt1.format(s.equivalent_revolutions);
    document.getElementById('avgSpeed').textContent=fmt1.format(s.avg_speed_m_s*3.6);
    document.getElementById('maxSpeed').textContent=fmt1.format(s.max_speed_m_s*3.6);
    document.getElementById('longestSession').textContent=duration(s.longest_session_s);
    document.getElementById('sessionCount').textContent=Math.round(s.session_count).toString();
    document.getElementById('heroNote').textContent=s.distance_m ? `${fmt.format(s.distance_m)} m total wheel travel` : 'No running recorded in this night window';
    const warning=document.getElementById('uncertainWarning');
    if (s.uncertain_duration_s > 0) {
      warning.classList.add('visible');
      warning.textContent=`Tracking was uncertain for ${duration(s.uncertain_duration_s)}. Ambiguous hidden rotations were intentionally not estimated.`;
    } else warning.classList.remove('visible');
    renderStatus(data.status); renderHours(data.hourly); renderTimeline(data.timeline); renderSessions(data.sessions); renderHistory(history);
  } catch (err) {
    document.getElementById('nightLabel').textContent='Unable to load tracker data';
    console.error(err);
  }
}
loadDashboard();
setInterval(loadDashboard, 15000);
</script>
</body>
</html>"""


def _bounds_for_night_date(value: date, rollover_hour: int = NIGHT_ROLLOVER_HOUR) -> Tuple[float, float]:
    start_dt = datetime.combine(value, datetime_time(hour=rollover_hour))
    next_date = value + timedelta(days=1)
    end_dt = datetime.combine(next_date, datetime_time(hour=rollover_hour))
    return start_dt.timestamp(), end_dt.timestamp()


def _night_bounds(now_ts: float, rollover_hour: int = NIGHT_ROLLOVER_HOUR) -> Tuple[float, float]:
    now = datetime.fromtimestamp(now_ts)
    night_date = now.date() if now.hour >= rollover_hour else now.date() - timedelta(days=1)
    return _bounds_for_night_date(night_date, rollover_hour)


def _local_day_bounds(now_ts: float) -> Tuple[float, float]:
    now = datetime.fromtimestamp(now_ts)
    start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    end = start + timedelta(days=1)
    return start.timestamp(), end.timestamp()


def _status(db: Database, now_ts: float) -> Dict[str, object]:
    latest = db.latest_activity()
    if latest is None:
        return {
            "available": False,
            "stale": True,
            "running": False,
            "tracking_state": "SEARCHING",
            "speed_m_s": 0.0,
            "age_s": None,
        }
    age_s = max(0.0, now_ts - float(latest["timestamp"]))
    return {
        "available": True,
        "stale": age_s > 10.0,
        "running": bool(latest["running"]),
        "tracking_state": latest["tracking_state"],
        "speed_m_s": float(latest["speed_m_s"]),
        "detection_quality": latest["detection_quality"],
        "last_sample_ts": float(latest["timestamp"]),
        "age_s": age_s,
    }


def _night_payload(db: Database, start_ts: float, end_ts: float, now_ts: float) -> Dict[str, object]:
    return {
        "start_ts": start_ts,
        "end_ts": end_ts,
        "summary": db.summary(start_ts, end_ts),
        "status": _status(db, now_ts),
        "hourly": db.hourly_activity(start_ts, end_ts),
        "timeline": db.activity_timeline(start_ts, min(end_ts, now_ts), max_points=240),
        "sessions": db.sessions(start_ts, end_ts, limit=50),
    }


def create_app(database_path: Optional[str] = None) -> FastAPI:
    app = FastAPI(title="Hamster Wheel Tracker", version="0.1.0")
    db_path = database_path or os.environ.get("HAMSTER_TRACKER_DB", "data/tracker.db")
    db = Database(db_path)
    app.state.database = db

    @app.on_event("shutdown")
    def close_database() -> None:
        db.close()

    @app.get("/", response_class=HTMLResponse)
    def dashboard() -> str:
        return DASHBOARD_HTML

    @app.get("/api/health")
    def health() -> Dict[str, object]:
        return {"ok": True, "camera": "not_configured", "database_path": db_path}

    @app.get("/api/status")
    def status() -> Dict[str, object]:
        return _status(db, time.time())

    @app.get("/api/dashboard")
    def dashboard_data(night: Optional[str] = Query(default=None)) -> Dict[str, object]:
        now_ts = time.time()
        if night is None:
            start_ts, end_ts = _night_bounds(now_ts)
        else:
            try:
                requested_date = date.fromisoformat(night)
            except ValueError as exc:
                raise HTTPException(status_code=400, detail="night must be YYYY-MM-DD") from exc
            start_ts, end_ts = _bounds_for_night_date(requested_date)
        return _night_payload(db, start_ts, end_ts, now_ts)

    @app.get("/api/history")
    def history(days: int = Query(default=7, ge=1, le=60)) -> List[Dict[str, float]]:
        now_ts = time.time()
        current_start, _ = _night_bounds(now_ts)
        current_date = datetime.fromtimestamp(current_start).date()
        rows = []
        for offset in range(days):
            start_ts, end_ts = _bounds_for_night_date(current_date - timedelta(days=offset))
            summary = db.summary(start_ts, end_ts)
            summary["start_ts"] = start_ts
            summary["end_ts"] = end_ts
            rows.append(summary)
        return rows

    @app.get("/api/today")
    def today() -> Dict[str, float]:
        start_ts, end_ts = _local_day_bounds(time.time())
        return db.summary(start_ts, end_ts)

    @app.get("/api/sessions")
    def sessions(night: Optional[str] = Query(default=None)) -> List[Dict[str, float]]:
        if night is None:
            start_ts, end_ts = _night_bounds(time.time())
        else:
            try:
                start_ts, end_ts = _bounds_for_night_date(date.fromisoformat(night))
            except ValueError as exc:
                raise HTTPException(status_code=400, detail="night must be YYYY-MM-DD") from exc
        return db.sessions(start_ts, end_ts)

    return app


app = create_app()
