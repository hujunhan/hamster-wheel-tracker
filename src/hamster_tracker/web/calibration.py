from pathlib import Path
from typing import Any, Dict

from fastapi import APIRouter, HTTPException
from fastapi.responses import HTMLResponse

from hamster_tracker.config import AppConfig


CALIBRATION_HTML = r"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <meta name="theme-color" content="#111315">
  <title>Calibration · Hamster Wheel Tracker</title>
  <style>
    :root {
      color-scheme: light dark;
      --bg:#f3f4f6; --surface:#fff; --surface2:#f7f7f8; --text:#151719;
      --muted:#6b7076; --border:#e3e5e8; --accent:#4e7c5b; --accent2:#376a46;
      --warn:#b36b24; --good:#3f8253;
    }
    @media (prefers-color-scheme: dark) {
      :root {
        --bg:#0f1113; --surface:#191c1f; --surface2:#202428; --text:#f4f5f6;
        --muted:#a2a8ae; --border:#2b3035; --accent:#7fbc91; --accent2:#94cda3;
        --warn:#e0a15f; --good:#7fbc91;
      }
    }
    * { box-sizing:border-box; }
    body { margin:0; background:var(--bg); color:var(--text); font-family:ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif; }
    main { max-width:1050px; margin:0 auto; padding:20px 14px 48px; }
    header { display:flex; justify-content:space-between; align-items:flex-start; gap:16px; margin-bottom:14px; }
    h1 { font-size:1.35rem; margin:0 0 4px; }
    h2 { font-size:.96rem; margin:0 0 12px; }
    .muted { color:var(--muted); }
    .subtitle { color:var(--muted); font-size:.82rem; }
    a { color:var(--accent); text-decoration:none; }
    .layout { display:grid; gap:12px; }
    @media (min-width:820px) { .layout { grid-template-columns:minmax(0,1.55fr) minmax(300px,.75fr); align-items:start; } }
    .panel { background:var(--surface); border:1px solid var(--border); border-radius:16px; padding:14px; }
    .preview-wrap { position:relative; overflow:hidden; border-radius:12px; background:#111; aspect-ratio:16/9; touch-action:none; }
    canvas { width:100%; height:100%; display:block; cursor:crosshair; }
    .preview-badge { position:absolute; left:10px; top:10px; font-size:.7rem; padding:5px 8px; border-radius:999px; background:rgba(0,0,0,.64); color:#fff; backdrop-filter:blur(4px); }
    .toolbar { display:flex; flex-wrap:wrap; gap:7px; margin-top:10px; }
    button { border:1px solid var(--border); background:var(--surface2); color:var(--text); border-radius:10px; padding:8px 10px; font:inherit; font-size:.78rem; cursor:pointer; }
    button.primary { background:var(--accent); color:#fff; border-color:transparent; }
    button.active { outline:2px solid var(--accent); }
    .steps { margin-top:10px; font-size:.78rem; color:var(--muted); line-height:1.45; }
    .field { margin-bottom:12px; }
    .field:last-child { margin-bottom:0; }
    label { display:flex; justify-content:space-between; align-items:baseline; gap:8px; font-size:.77rem; color:var(--muted); margin-bottom:5px; }
    input[type=number], input[type=text] { width:100%; border:1px solid var(--border); background:var(--surface2); color:var(--text); border-radius:9px; padding:9px 10px; font:inherit; }
    input[type=range] { width:100%; accent-color:var(--accent); }
    .two { display:grid; grid-template-columns:1fr 1fr; gap:8px; }
    .three { display:grid; grid-template-columns:repeat(3,1fr); gap:7px; }
    .mini-label { font-size:.66rem; color:var(--muted); margin-bottom:3px; }
    .status { min-height:38px; border-radius:10px; background:var(--surface2); padding:9px 10px; font-size:.76rem; line-height:1.35; }
    .status.good { color:var(--good); }
    .status.warn { color:var(--warn); }
    .readout { display:grid; grid-template-columns:1fr 1fr; gap:7px; margin-top:9px; }
    .readout div { background:var(--surface2); border-radius:9px; padding:8px; }
    .readout span { display:block; color:var(--muted); font-size:.64rem; margin-bottom:3px; }
    .readout strong { font-size:.82rem; font-variant-numeric:tabular-nums; }
    .save-row { display:flex; gap:8px; align-items:center; margin-top:12px; }
    .save-row button { flex:1; }
    code { font-size:.72rem; }
  </style>
</head>
<body>
<main>
  <header>
    <div>
      <h1>Calibration</h1>
      <div class="subtitle">Wheel geometry and marker constraints</div>
    </div>
    <a href="/">← Dashboard</a>
  </header>

  <div class="layout">
    <section class="panel">
      <h2>Preview</h2>
      <div class="preview-wrap" id="previewWrap">
        <canvas id="preview" width="1280" height="720"></canvas>
        <div class="preview-badge" id="previewBadge">Synthetic preview · camera not connected</div>
      </div>
      <div class="toolbar">
        <button id="centerBtn" onclick="setMode('center')">1 · Set wheel center</button>
        <button id="edgeBtn" onclick="setMode('edge')">2 · Set wheel edge</button>
        <button id="markerBtn" onclick="setMode('marker')">3 · Set marker radius</button>
        <button onclick="resetView()">Reset view</button>
      </div>
      <div class="steps" id="hint">Tap “Set wheel center”, then tap the wheel hub in the preview.</div>
      <div class="readout">
        <div><span>Wheel center</span><strong id="centerReadout">—</strong></div>
        <div><span>Wheel radius</span><strong id="radiusReadout">—</strong></div>
        <div><span>Marker radius</span><strong id="markerReadout">—</strong></div>
        <div><span>Allowed annulus</span><strong id="annulusReadout">—</strong></div>
      </div>
    </section>

    <div>
      <section class="panel">
        <h2>Wheel</h2>
        <div class="field">
          <label>Effective running diameter <span id="diameterLabel">228.6 mm</span></label>
          <input id="diameter" type="number" min="20" max="1000" step="0.1" value="228.6" oninput="render()">
        </div>
        <div class="field">
          <label>Marker radius ratio <span id="ratioLabel">0.75 R</span></label>
          <input id="markerRatio" type="range" min="0.05" max="1" step="0.01" value="0.75" oninput="render()">
        </div>
        <div class="field">
          <label>Annulus tolerance <span id="toleranceLabel">±0.12 R</span></label>
          <input id="markerTolerance" type="range" min="0.01" max="0.5" step="0.01" value="0.12" oninput="render()">
        </div>
      </section>

      <section class="panel" style="margin-top:12px">
        <h2>Marker HSV</h2>
        <div class="field">
          <label>Lower bound</label>
          <div class="three">
            <div><div class="mini-label">H · 0–179</div><input id="hLow" type="number" min="0" max="179" value="40"></div>
            <div><div class="mini-label">S · 0–255</div><input id="sLow" type="number" min="0" max="255" value="80"></div>
            <div><div class="mini-label">V · 0–255</div><input id="vLow" type="number" min="0" max="255" value="80"></div>
          </div>
        </div>
        <div class="field">
          <label>Upper bound</label>
          <div class="three">
            <div><div class="mini-label">H · 0–179</div><input id="hHigh" type="number" min="0" max="179" value="80"></div>
            <div><div class="mini-label">S · 0–255</div><input id="sHigh" type="number" min="0" max="255" value="255"></div>
            <div><div class="mini-label">V · 0–255</div><input id="vHigh" type="number" min="0" max="255" value="255"></div>
          </div>
        </div>
        <div class="subtitle">Real camera color sampling will be added when the Jetson camera is available. For now these values are editable and persist normally.</div>
      </section>

      <section class="panel" style="margin-top:12px">
        <h2>Save</h2>
        <div class="status" id="status">Loading current configuration…</div>
        <div class="save-row">
          <button onclick="loadConfig()">Reload</button>
          <button class="primary" onclick="saveConfig()">Save calibration</button>
        </div>
      </section>
    </div>
  </div>
</main>
<script>
const canvas=document.getElementById('preview');
const ctx=canvas.getContext('2d');
let mode='center';
let config=null;
let geometry={cx:640, cy:360, radius:270};

function setMode(next) {
  mode=next;
  ['center','edge','marker'].forEach(x => document.getElementById(x+'Btn').classList.toggle('active', x===next));
  const text={
    center:'Tap the wheel hub / rotation center.',
    edge:'Tap a point on the outer wheel edge. Radius is measured from the saved center.',
    marker:'Tap the marker center. Only its radial distance is used; the recommended target is about 0.75 R.'
  };
  document.getElementById('hint').textContent=text[next];
}

function canvasPoint(event) {
  const r=canvas.getBoundingClientRect();
  return {
    x:(event.clientX-r.left)*canvas.width/r.width,
    y:(event.clientY-r.top)*canvas.height/r.height
  };
}
canvas.addEventListener('pointerdown', event => {
  const p=canvasPoint(event);
  if (mode==='center') {
    geometry.cx=p.x; geometry.cy=p.y; setMode('edge');
  } else if (mode==='edge') {
    geometry.radius=Math.max(10, Math.hypot(p.x-geometry.cx,p.y-geometry.cy)); setMode('marker');
  } else {
    const r=Math.hypot(p.x-geometry.cx,p.y-geometry.cy);
    document.getElementById('markerRatio').value=Math.max(.05,Math.min(1,r/geometry.radius)).toFixed(2);
  }
  render();
});

function number(id) { return Number(document.getElementById(id).value); }
function updateLabels() {
  const ratio=number('markerRatio'), tol=number('markerTolerance');
  document.getElementById('diameterLabel').textContent=`${number('diameter').toFixed(1)} mm`;
  document.getElementById('ratioLabel').textContent=`${ratio.toFixed(2)} R`;
  document.getElementById('toleranceLabel').textContent=`±${tol.toFixed(2)} R`;
  document.getElementById('centerReadout').textContent=`(${geometry.cx.toFixed(0)}, ${geometry.cy.toFixed(0)}) px`;
  document.getElementById('radiusReadout').textContent=`${geometry.radius.toFixed(1)} px`;
  document.getElementById('markerReadout').textContent=`${(geometry.radius*ratio).toFixed(1)} px · ${ratio.toFixed(2)} R`;
  document.getElementById('annulusReadout').textContent=`${(geometry.radius*Math.max(.01,ratio-tol)).toFixed(0)}–${(geometry.radius*Math.min(1.2,ratio+tol)).toFixed(0)} px`;
}

function render() {
  updateLabels();
  const W=canvas.width,H=canvas.height;
  ctx.clearRect(0,0,W,H);
  const grad=ctx.createLinearGradient(0,0,W,H); grad.addColorStop(0,'#20252a'); grad.addColorStop(1,'#101315');
  ctx.fillStyle=grad; ctx.fillRect(0,0,W,H);
  ctx.strokeStyle='rgba(255,255,255,.035)'; ctx.lineWidth=1;
  for(let x=0;x<W;x+=80){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,H);ctx.stroke();}
  for(let y=0;y<H;y+=80){ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(W,y);ctx.stroke();}

  const ratio=number('markerRatio'), tol=number('markerTolerance');
  const inner=geometry.radius*Math.max(0,ratio-tol), outer=geometry.radius*Math.min(1.2,ratio+tol);
  ctx.beginPath(); ctx.arc(geometry.cx,geometry.cy,outer,0,Math.PI*2); ctx.arc(geometry.cx,geometry.cy,inner,0,Math.PI*2,true);
  ctx.fillStyle='rgba(127,188,145,.13)'; ctx.fill('evenodd');
  ctx.strokeStyle='rgba(127,188,145,.6)'; ctx.setLineDash([8,8]); ctx.lineWidth=2;
  [inner,outer].forEach(r=>{ctx.beginPath();ctx.arc(geometry.cx,geometry.cy,r,0,Math.PI*2);ctx.stroke();}); ctx.setLineDash([]);

  ctx.strokeStyle='rgba(255,255,255,.72)'; ctx.lineWidth=4; ctx.beginPath(); ctx.arc(geometry.cx,geometry.cy,geometry.radius,0,Math.PI*2); ctx.stroke();
  ctx.fillStyle='#fff'; ctx.beginPath(); ctx.arc(geometry.cx,geometry.cy,6,0,Math.PI*2); ctx.fill();
  ctx.strokeStyle='rgba(255,255,255,.75)'; ctx.lineWidth=2; ctx.beginPath(); ctx.moveTo(geometry.cx-17,geometry.cy);ctx.lineTo(geometry.cx+17,geometry.cy);ctx.moveTo(geometry.cx,geometry.cy-17);ctx.lineTo(geometry.cx,geometry.cy+17);ctx.stroke();

  const mr=geometry.radius*ratio, angle=-.55;
  const mx=geometry.cx+Math.cos(angle)*mr, my=geometry.cy+Math.sin(angle)*mr;
  ctx.fillStyle='#60e36f';ctx.shadowColor='#60e36f';ctx.shadowBlur=14;ctx.beginPath();ctx.arc(mx,my,15,0,Math.PI*2);ctx.fill();ctx.shadowBlur=0;
  ctx.fillStyle='rgba(255,255,255,.55)';ctx.font='20px system-ui';ctx.fillText('synthetic marker',mx+25,my+6);
}

function applyConfig(raw) {
  config=raw;
  geometry.cx=Number(raw.wheel.center_px[0]); geometry.cy=Number(raw.wheel.center_px[1]); geometry.radius=Number(raw.wheel.radius_px);
  document.getElementById('diameter').value=raw.wheel.effective_running_diameter_mm;
  document.getElementById('markerRatio').value=raw.wheel.marker_radius_ratio;
  document.getElementById('markerTolerance').value=raw.wheel.marker_radius_tolerance_ratio;
  [ ['hLow',raw.marker.hsv_lower[0]],['sLow',raw.marker.hsv_lower[1]],['vLow',raw.marker.hsv_lower[2]],['hHigh',raw.marker.hsv_upper[0]],['sHigh',raw.marker.hsv_upper[1]],['vHigh',raw.marker.hsv_upper[2]] ].forEach(([id,v])=>document.getElementById(id).value=v);
  render();
}

async function loadConfig() {
  const status=document.getElementById('status');
  try {
    const response=await fetch('/api/calibration');
    if(!response.ok) throw new Error(await response.text());
    const payload=await response.json(); applyConfig(payload.config);
    status.className='status good'; status.textContent=payload.saved ? `Loaded ${payload.config_path}` : `Using defaults · saving will create ${payload.config_path}`;
    document.getElementById('previewBadge').textContent=payload.camera_available ? 'Live camera preview' : 'Synthetic preview · camera not connected';
  } catch(err) {
    status.className='status warn'; status.textContent=`Unable to load configuration: ${err}`;
  }
}

function payload() {
  const base=JSON.parse(JSON.stringify(config || {}));
  base.wheel=base.wheel||{}; base.marker=base.marker||{};
  base.wheel.center_px=[geometry.cx,geometry.cy]; base.wheel.radius_px=geometry.radius;
  base.wheel.effective_running_diameter_mm=number('diameter');
  base.wheel.marker_radius_ratio=number('markerRatio'); base.wheel.marker_radius_tolerance_ratio=number('markerTolerance');
  base.marker.hsv_lower=[number('hLow'),number('sLow'),number('vLow')];
  base.marker.hsv_upper=[number('hHigh'),number('sHigh'),number('vHigh')];
  return base;
}

async function saveConfig() {
  const status=document.getElementById('status'); status.className='status'; status.textContent='Saving…';
  try {
    const response=await fetch('/api/calibration',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload())});
    const result=await response.json();
    if(!response.ok) throw new Error(result.detail || JSON.stringify(result));
    applyConfig(result.config); status.className='status good'; status.textContent=`Saved calibration to ${result.config_path}`;
  } catch(err) {
    status.className='status warn'; status.textContent=`Not saved: ${err}`;
  }
}

function resetView() { geometry={cx:640,cy:360,radius:270}; document.getElementById('markerRatio').value=.75; render(); setMode('center'); }
setMode('center'); loadConfig(); render();
</script>
</body>
</html>"""


def build_calibration_router(config_path: str) -> APIRouter:
    router = APIRouter()

    @router.get("/calibration", response_class=HTMLResponse)
    def calibration_page() -> str:
        return CALIBRATION_HTML

    @router.get("/api/calibration")
    def calibration_config() -> Dict[str, Any]:
        try:
            config = AppConfig.load_or_default(config_path)
        except (OSError, ValueError, TypeError) as exc:
            raise HTTPException(status_code=500, detail="invalid saved configuration: {}".format(exc)) from exc
        return {
            "config": config.to_dict(),
            "config_path": config_path,
            "saved": Path(config_path).exists(),
            "camera_available": False,
            "preview_width": 1280,
            "preview_height": 720,
        }

    @router.post("/api/calibration")
    def save_calibration(payload: Dict[str, Any]) -> Dict[str, Any]:
        try:
            config = AppConfig.from_dict(payload)
            config.save(config_path)
        except (OSError, ValueError, TypeError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return {
            "ok": True,
            "config": config.to_dict(),
            "config_path": config_path,
        }

    return router
