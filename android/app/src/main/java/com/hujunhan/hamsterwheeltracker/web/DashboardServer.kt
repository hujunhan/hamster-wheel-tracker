package com.hujunhan.hamsterwheeltracker.web

import com.hujunhan.hamsterwheeltracker.persistence.LiveTrackerSnapshot
import com.hujunhan.hamsterwheeltracker.persistence.ReportingNight
import com.hujunhan.hamsterwheeltracker.persistence.TrackingDao
import fi.iki.elonen.NanoHTTPD
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max

/** Runtime-only diagnostics surfaced by the LAN dashboard. */
data class DashboardRuntimeSnapshot(
    val serviceTracking: Boolean,
    val analysisEnabled: Boolean,
    val serviceMessage: String,
    val analysisFps: Double?,
    val frameWidth: Int?,
    val frameHeight: Int?,
    val latestFrameGapMs: Double?,
    val maxFrameGapMs: Double?,
    val totalFrames: Long?,
    val lastAnalysisEpochMs: Long?,
)

class DashboardServer(
    private val dao: TrackingDao,
    private val liveProvider: () -> LiveTrackerSnapshot?,
    private val runtimeProvider: () -> DashboardRuntimeSnapshot? = { null },
    port: Int = DEFAULT_PORT,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val response = when (session.uri) {
            "/", "/index.html" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", dashboardHtml)
            "/api/dashboard" -> newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=utf-8",
                dashboardJson(System.currentTimeMillis()),
            )
            "/health" -> newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "ok")
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "not found")
        }
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("X-Content-Type-Options", "nosniff")
        return response
    }

    private data class HistoryPoint(
        val startEpochMs: Long,
        val endEpochMs: Long,
        val distanceM: Double,
        val movingDurationSec: Double,
        val maxSpeedMS: Double,
        val sessionCount: Int,
    )

    private fun dashboardJson(nowEpochMs: Long): String {
        val window = ReportingNight.containing(nowEpochMs, zoneId)
        val summary = dao.summaryBetween(window.startEpochMs, window.endEpochMs)
        val sessions = dao.sessionsBetween(window.startEpochMs, window.endEpochMs)
        val hourly = dao.hourlyBetween(window.startEpochMs, window.endEpochMs)
        val longestSessionSec = sessions.maxOfOrNull { it.durationSec } ?: 0.0
        val averageSpeedMS = if (summary.movingDurationSec > 0.0) {
            summary.distanceM / summary.movingDurationSec
        } else {
            0.0
        }

        val history = mutableListOf(
            HistoryPoint(
                startEpochMs = window.startEpochMs,
                endEpochMs = window.endEpochMs,
                distanceM = summary.distanceM,
                movingDurationSec = summary.movingDurationSec,
                maxSpeedMS = summary.maxSpeedMS,
                sessionCount = sessions.size,
            ),
        )
        for (daysBack in 1L until HISTORY_NIGHTS) {
            val historicWindow = ReportingNight.previous(window, daysBack, zoneId)
            val historic = dao.summaryBetween(historicWindow.startEpochMs, historicWindow.endEpochMs)
            val historicSessions = dao.sessionsBetween(historicWindow.startEpochMs, historicWindow.endEpochMs)
            history += HistoryPoint(
                startEpochMs = historicWindow.startEpochMs,
                endEpochMs = historicWindow.endEpochMs,
                distanceM = historic.distanceM,
                movingDurationSec = historic.movingDurationSec,
                maxSpeedMS = historic.maxSpeedMS,
                sessionCount = historicSessions.size,
            )
        }

        val completedHistory = history.drop(1).filter {
            it.distanceM > 0.0 || it.movingDurationSec > 0.0 || it.sessionCount > 0
        }
        val recentAverageDistanceM = completedHistory.take(RECENT_AVERAGE_NIGHTS).takeIf { it.isNotEmpty() }
            ?.map { it.distanceM }
            ?.average()
        val distanceVsAveragePct = recentAverageDistanceM?.takeIf { it > 0.001 }?.let {
            (summary.distanceM - it) * 100.0 / it
        }

        val historyJson = history.joinToString(",") { item ->
            """{"startEpochMs":${item.startEpochMs},"endEpochMs":${item.endEpochMs},"distanceM":${number(item.distanceM)},"movingDurationSec":${number(item.movingDurationSec)},"maxSpeedMS":${number(item.maxSpeedMS)},"sessionCount":${item.sessionCount}}"""
        }

        val hourlyJson = hourly.joinToString(",") { row ->
            """{"hourStartEpochMs":${row.hourStartEpochMs},"distanceM":${number(row.distanceM)},"movingDurationSec":${number(row.movingDurationSec)},"maxSpeedMS":${number(row.maxSpeedMS)}}"""
        }

        val sessionsJson = sessions.take(MAX_SESSIONS).joinToString(",") { item ->
            """{"startEpochMs":${item.startEpochMs},"endEpochMs":${item.endEpochMs},"durationSec":${number(item.durationSec)},"movingDurationSec":${number(item.movingDurationSec)},"distanceM":${number(item.distanceM)},"revolutions":${number(item.revolutions)},"averageSpeedMS":${number(item.averageSpeedMS)},"maxSpeedMS":${number(item.maxSpeedMS)}}"""
        }

        val live = liveProvider()
        val liveJson = live?.let { value ->
            val snapshot = value.snapshot
            """{"updatedEpochMs":${value.wallClockEpochMs},"trackingState":${jsonString(snapshot.trackingState.name)},"markerVisible":${snapshot.markerVisible},"running":${snapshot.running},"rawSpeedMS":${number(snapshot.rawSpeedMS)},"displaySpeedMS":${number(snapshot.displaySpeedMS)},"totalDistanceM":${number(snapshot.totalDistanceM)},"totalRevolutions":${number(snapshot.equivalentRevolutions)},"reason":${jsonString(snapshot.lastReason)}}"""
        } ?: "null"

        val runtime = runtimeProvider()
        val analysisAgeMs = runtime?.lastAnalysisEpochMs?.let { max(0L, nowEpochMs - it) }
        val healthStatus = when {
            runtime == null -> "unknown"
            !runtime.serviceTracking -> "stopped"
            !runtime.analysisEnabled -> "paused"
            analysisAgeMs == null || analysisAgeMs > STALE_ANALYSIS_MS -> "stale"
            live == null -> "warning"
            live.snapshot.trackingState.name == "UNCERTAIN" -> "warning"
            !live.snapshot.markerVisible && live.snapshot.trackingState.name != "PREDICTING" -> "warning"
            else -> "healthy"
        }
        val healthJson = if (runtime == null) {
            """{"status":"unknown","message":"Runtime diagnostics unavailable","analysisAgeMs":null,"analysisEnabled":null,"analysisFps":null,"frameWidth":null,"frameHeight":null,"latestFrameGapMs":null,"maxFrameGapMs":null,"totalFrames":null}"""
        } else {
            """{"status":${jsonString(healthStatus)},"message":${jsonString(runtime.serviceMessage)},"analysisAgeMs":${analysisAgeMs ?: "null"},"analysisEnabled":${runtime.analysisEnabled},"analysisFps":${nullableNumber(runtime.analysisFps)},"frameWidth":${runtime.frameWidth ?: "null"},"frameHeight":${runtime.frameHeight ?: "null"},"latestFrameGapMs":${nullableNumber(runtime.latestFrameGapMs)},"maxFrameGapMs":${nullableNumber(runtime.maxFrameGapMs)},"totalFrames":${runtime.totalFrames ?: "null"}}"""
        }

        return """{"nowEpochMs":$nowEpochMs,"windowStartEpochMs":${window.startEpochMs},"windowEndEpochMs":${window.endEpochMs},"summary":{"distanceM":${number(summary.distanceM)},"revolutions":${number(summary.revolutions)},"movingDurationSec":${number(summary.movingDurationSec)},"uncertainDurationSec":${number(summary.uncertainDurationSec)},"averageSpeedMS":${number(averageSpeedMS)},"maxSpeedMS":${number(summary.maxSpeedMS)},"sessionCount":${sessions.size},"longestSessionSec":${number(longestSessionSec)}},"comparison":{"recentAverageDistanceM":${nullableNumber(recentAverageDistanceM)},"distanceVsAveragePct":${nullableNumber(distanceVsAveragePct)},"sampleNights":${completedHistory.take(RECENT_AVERAGE_NIGHTS).size}},"health":$healthJson,"live":$liveJson,"hourly":[$hourlyJson],"sessions":[$sessionsJson],"history":[$historyJson]}"""
    }

    private fun number(value: Double): String = if (value.isFinite()) {
        String.format(Locale.US, "%.6f", value)
    } else {
        "0.0"
    }

    private fun nullableNumber(value: Double?): String = if (value != null && value.isFinite()) {
        number(value)
    } else {
        "null"
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append(' ') else append(char)
            }
        }
        append('"')
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val HISTORY_NIGHTS = 8L
        private const val RECENT_AVERAGE_NIGHTS = 7
        private const val MAX_SESSIONS = 30
        private const val STALE_ANALYSIS_MS = 5_000L

        private val dashboardHtml = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#101318">
<title>Hamster Wheel</title>
<style>
:root{color-scheme:dark;font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#0b0d10;color:#eef2f6;--panel:#15191e;--line:#252b33;--muted:#96a1ad;--good:#45d483;--warn:#f2b84b;--bad:#ff6b6b;--blue:#72a7ff}
*{box-sizing:border-box}body{margin:0;padding:18px;max-width:980px;margin-inline:auto}header{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}h1{font-size:1.45rem;margin:0 0 4px}.muted{color:var(--muted)}.tiny{font-size:.76rem}.health{margin:14px 0;display:flex;gap:12px;align-items:center;background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:12px}.dot{width:12px;height:12px;border-radius:50%;background:#77808a;flex:none;box-shadow:0 0 0 4px #ffffff0a}.dot.healthy{background:var(--good)}.dot.warning,.dot.paused{background:var(--warn)}.dot.stale,.dot.stopped,.dot.offline{background:var(--bad)}.health-main{min-width:0;flex:1}.health-title{font-weight:750}.health-detail{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:var(--muted);font-size:.82rem;margin-top:2px}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(135px,1fr));gap:10px}.card,section{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:12px}.value{font-size:1.42rem;font-weight:750;margin-top:4px;letter-spacing:-.02em}.subvalue{font-size:.76rem;color:var(--muted);margin-top:3px;min-height:1em}.positive{color:var(--good)}.negative{color:var(--warn)}section{margin-top:12px}section h2{font-size:1rem;margin:0 0 10px}.chart-title{display:flex;justify-content:space-between;align-items:baseline;gap:8px}.bars{display:flex;align-items:flex-end;gap:5px;height:150px;overflow-x:auto;padding-top:8px}.barwrap{min-width:30px;height:100%;display:flex;flex-direction:column;justify-content:flex-end;align-items:center}.bar{width:21px;min-height:2px;background:var(--blue);border-radius:5px 5px 1px 1px}.bar.today{background:var(--good)}.label{font-size:10px;color:#8995a2;margin-top:5px}.trend{display:grid;grid-template-columns:repeat(8,minmax(28px,1fr));gap:5px;height:145px;align-items:end;padding-top:8px}.trendwrap{height:100%;display:flex;flex-direction:column;justify-content:flex-end;align-items:center}.trendbar{width:min(34px,80%);min-height:2px;background:#52657f;border-radius:5px 5px 1px 1px}.trendbar.today{background:var(--good)}table{width:100%;border-collapse:collapse;font-size:.84rem}th,td{text-align:right;padding:7px 5px;border-bottom:1px solid var(--line);white-space:nowrap}th:first-child,td:first-child{text-align:left}.warningbox{color:var(--warn);border-color:#6a5222;background:#211c12}.diag{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:8px}.diag>div{background:#101318;border-radius:9px;padding:9px}.diag strong{display:block;font-size:.96rem;margin-top:2px}@media(max-width:520px){body{padding:12px;padding-bottom:max(12px,env(safe-area-inset-bottom))}.cards{grid-template-columns:repeat(2,1fr)}header{display:block}.trend{gap:2px}.hide-small{display:none}}
</style>
</head>
<body>
<header><div><h1>Hamster Wheel</h1><div id="window" class="muted">Loading current reporting night…</div></div><div id="refreshed" class="muted tiny"></div></header>
<div id="health" class="health"><span id="dot" class="dot"></span><div class="health-main"><div id="healthTitle" class="health-title">Connecting…</div><div id="healthDetail" class="health-detail">Waiting for tracker</div></div></div>
<div class="cards">
  <div class="card"><div class="muted">Distance</div><div id="distance" class="value">—</div><div id="comparison" class="subvalue"></div></div>
  <div class="card"><div class="muted">Moving time</div><div id="moving" class="value">—</div><div id="longest" class="subvalue"></div></div>
  <div class="card"><div class="muted">Revolutions</div><div id="revs" class="value">—</div></div>
  <div class="card"><div class="muted">Live speed</div><div id="liveSpeed" class="value">—</div><div id="liveState" class="subvalue"></div></div>
  <div class="card"><div class="muted">Average speed</div><div id="avg" class="value">—</div></div>
  <div class="card"><div class="muted">Max speed</div><div id="max" class="value">—</div></div>
  <div class="card"><div class="muted">Sessions</div><div id="sessionsCount" class="value">—</div></div>
  <div class="card"><div class="muted">Uncertain</div><div id="uncertainValue" class="value">—</div></div>
</div>
<section><div class="chart-title"><h2>Hourly distance</h2><span class="muted tiny">current reporting night</span></div><div id="bars" class="bars"></div></section>
<section><div class="chart-title"><h2>Nightly distance</h2><span id="trendCaption" class="muted tiny">today + previous nights</span></div><div id="trend" class="trend"></div></section>
<section id="warningSection" style="display:none"><h2>Tracking quality</h2><div id="warningText"></div></section>
<section><h2>Tracker health</h2><div class="diag"><div><span class="muted tiny">Analysis</span><strong id="diagFps">—</strong></div><div><span class="muted tiny">Frame</span><strong id="diagFrame">—</strong></div><div><span class="muted tiny">Latest gap</span><strong id="diagGap">—</strong></div><div><span class="muted tiny">Last frame</span><strong id="diagAge">—</strong></div></div></section>
<section><h2>Recent sessions</h2><div style="overflow-x:auto"><table><thead><tr><th>Start</th><th>Distance</th><th>Moving</th><th>Avg</th><th>Max</th></tr></thead><tbody id="sessions"></tbody></table></div></section>
<section><h2>Recent nights</h2><div style="overflow-x:auto"><table><thead><tr><th>Night</th><th>Distance</th><th>Moving</th><th>Sessions</th></tr></thead><tbody id="history"></tbody></table></div></section>
<script>
const fmtTime=s=>{s=Math.max(0,s||0);const h=Math.floor(s/3600),m=Math.floor((s%3600)/60),sec=Math.floor(s%60);return h>0?h+'h '+m+'m':m>0?m+'m':sec+'s'};
const fmtClock=ms=>new Date(ms).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
const fmtNight=ms=>new Date(ms).toLocaleDateString([], {month:'short',day:'numeric'});
const set=(id,v)=>document.getElementById(id).textContent=v;
const n=v=>typeof v==='number'&&Number.isFinite(v);
function setHealth(status,title,detail){const dot=document.getElementById('dot');dot.className='dot '+status;set('healthTitle',title);set('healthDetail',detail||'');}
function healthTitle(status){return ({healthy:'Tracker healthy',warning:'Tracker needs attention',stale:'Camera analysis stale',paused:'Analysis paused',stopped:'Tracking stopped',unknown:'Tracker status unknown'})[status]||'Tracker status';}
async function refresh(){
 try{
  const r=await fetch('/api/dashboard',{cache:'no-store'});if(!r.ok)throw new Error('HTTP '+r.status);const d=await r.json(),s=d.summary,h=d.health,c=d.comparison;
  set('window',fmtNight(d.windowStartEpochMs)+' 18:00 → '+fmtNight(d.windowEndEpochMs)+' 18:00');set('refreshed','Updated '+fmtClock(Date.now()));
  set('distance',s.distanceM.toFixed(2)+' m');set('moving',fmtTime(s.movingDurationSec));set('revs',s.revolutions.toFixed(1));set('avg',s.averageSpeedMS.toFixed(3)+' m/s');set('max',s.maxSpeedMS.toFixed(3)+' m/s');set('sessionsCount',String(s.sessionCount));set('uncertainValue',fmtTime(s.uncertainDurationSec));set('longest',s.longestSessionSec>0?'Longest '+fmtTime(s.longestSessionSec):'No completed session');
  if(n(c.recentAverageDistanceM)){const pct=n(c.distanceVsAveragePct)?c.distanceVsAveragePct:null;set('comparison',(pct===null?'Recent avg '+c.recentAverageDistanceM.toFixed(1)+' m':(pct>=0?'+':'')+pct.toFixed(0)+'% vs '+c.sampleNights+'-night avg'));document.getElementById('comparison').className='subvalue '+(pct!==null&&pct>=0?'positive':'negative')}else{set('comparison','Building history')}
  if(d.live){set('liveSpeed',d.live.displaySpeedMS.toFixed(3)+' m/s');set('liveState',d.live.trackingState+(d.live.running?' · running':'')+(d.live.markerVisible?' · marker':' · no marker'))}else{set('liveSpeed','—');set('liveState','Waiting for tracker')}
  const age=h.analysisAgeMs===null?null:Math.max(0,h.analysisAgeMs);const detail=[h.message,n(h.analysisFps)?h.analysisFps.toFixed(1)+' FPS':null,h.frameWidth&&h.frameHeight?h.frameWidth+'×'+h.frameHeight:null,age!==null?Math.round(age/1000)+'s ago':null].filter(Boolean).join(' · ');setHealth(h.status,healthTitle(h.status),detail);
  set('diagFps',n(h.analysisFps)?h.analysisFps.toFixed(1)+' FPS':'—');set('diagFrame',h.frameWidth&&h.frameHeight?h.frameWidth+' × '+h.frameHeight:'—');set('diagGap',n(h.latestFrameGapMs)?h.latestFrameGapMs.toFixed(1)+' ms':'—');set('diagAge',age!==null?(age<1000?'<1s':Math.round(age/1000)+'s'):'—');
  const maxD=Math.max(0.001,...d.hourly.map(x=>x.distanceM));document.getElementById('bars').innerHTML=d.hourly.length?d.hourly.map(x=>'<div class="barwrap" title="'+x.distanceM.toFixed(2)+' m"><div class="bar" style="height:'+Math.max(2,130*x.distanceM/maxD)+'px"></div><div class="label">'+fmtClock(x.hourStartEpochMs)+'</div></div>').join(''):'<div class="muted">No movement recorded yet</div>';
  const chronological=[...d.history].reverse(),maxNight=Math.max(0.001,...chronological.map(x=>x.distanceM));document.getElementById('trend').innerHTML=chronological.map((x,i)=>'<div class="trendwrap" title="'+x.distanceM.toFixed(2)+' m"><div class="trendbar '+(i===chronological.length-1?'today':'')+'" style="height:'+Math.max(2,115*x.distanceM/maxNight)+'px"></div><div class="label">'+fmtNight(x.startEpochMs)+'</div></div>').join('');
  document.getElementById('sessions').innerHTML=d.sessions.map(x=>'<tr><td>'+fmtClock(x.startEpochMs)+'</td><td>'+x.distanceM.toFixed(2)+' m</td><td>'+fmtTime(x.movingDurationSec)+'</td><td>'+x.averageSpeedMS.toFixed(3)+'</td><td>'+x.maxSpeedMS.toFixed(3)+'</td></tr>').join('')||'<tr><td colspan="5" class="muted">No completed sessions yet</td></tr>';
  document.getElementById('history').innerHTML=d.history.map((x,i)=>'<tr><td>'+(i===0?'Today · ':'')+fmtNight(x.startEpochMs)+'</td><td>'+x.distanceM.toFixed(2)+' m</td><td>'+fmtTime(x.movingDurationSec)+'</td><td>'+x.sessionCount+'</td></tr>').join('');
  const warning=document.getElementById('warningSection');if(s.uncertainDurationSec>1||h.status==='warning'||h.status==='stale'){warning.style.display='block';warning.className='warningbox';set('warningText',(s.uncertainDurationSec>1?'Uncertain tracking: '+fmtTime(s.uncertainDurationSec)+'. Ambiguous motion is intentionally not guessed. ':'')+(h.status==='stale'?'Camera frames are not arriving; check the tracker phone. ':'')+(h.status==='warning'?'Marker/tracker state currently needs attention.':''))}else{warning.style.display='none'}
 }catch(e){setHealth('offline','Dashboard connection lost',String(e));set('refreshed','Retrying…')}
}
refresh();setInterval(refresh,3000);
</script>
</body>
</html>
        """.trimIndent()
    }
}
