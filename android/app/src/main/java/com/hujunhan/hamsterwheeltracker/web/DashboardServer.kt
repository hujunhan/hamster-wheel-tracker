package com.hujunhan.hamsterwheeltracker.web

import com.hujunhan.hamsterwheeltracker.persistence.LiveTrackerSnapshot
import com.hujunhan.hamsterwheeltracker.persistence.ReportingNight
import com.hujunhan.hamsterwheeltracker.persistence.TrackingDao
import fi.iki.elonen.NanoHTTPD
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max

class DashboardServer(
    private val dao: TrackingDao,
    private val liveProvider: () -> LiveTrackerSnapshot?,
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

        val historyJson = (0L until HISTORY_NIGHTS).joinToString(",") { daysBack ->
            val historicWindow = ReportingNight.previous(window, daysBack, zoneId)
            val historic = dao.summaryBetween(historicWindow.startEpochMs, historicWindow.endEpochMs)
            val historicSessions = dao.sessionsBetween(historicWindow.startEpochMs, historicWindow.endEpochMs)
            """{"startEpochMs":${historicWindow.startEpochMs},"endEpochMs":${historicWindow.endEpochMs},"distanceM":${number(historic.distanceM)},"movingDurationSec":${number(historic.movingDurationSec)},"maxSpeedMS":${number(historic.maxSpeedMS)},"sessionCount":${historicSessions.size}}"""
        }

        val hourlyJson = hourly.joinToString(",") { row ->
            """{"hourStartEpochMs":${row.hourStartEpochMs},"distanceM":${number(row.distanceM)},"movingDurationSec":${number(row.movingDurationSec)},"maxSpeedMS":${number(row.maxSpeedMS)}}"""
        }

        val sessionsJson = sessions.take(MAX_SESSIONS).joinToString(",") { item ->
            """{"startEpochMs":${item.startEpochMs},"endEpochMs":${item.endEpochMs},"durationSec":${number(item.durationSec)},"movingDurationSec":${number(item.movingDurationSec)},"distanceM":${number(item.distanceM)},"revolutions":${number(item.revolutions)},"averageSpeedMS":${number(item.averageSpeedMS)},"maxSpeedMS":${number(item.maxSpeedMS)}}"""
        }

        val liveJson = liveProvider()?.let { live ->
            val snapshot = live.snapshot
            """{"updatedEpochMs":${live.wallClockEpochMs},"trackingState":${jsonString(snapshot.trackingState.name)},"markerVisible":${snapshot.markerVisible},"running":${snapshot.running},"rawSpeedMS":${number(snapshot.rawSpeedMS)},"displaySpeedMS":${number(snapshot.displaySpeedMS)},"totalDistanceM":${number(snapshot.totalDistanceM)},"totalRevolutions":${number(snapshot.equivalentRevolutions)},"reason":${jsonString(snapshot.lastReason)}}"""
        } ?: "null"

        return """{"nowEpochMs":$nowEpochMs,"windowStartEpochMs":${window.startEpochMs},"windowEndEpochMs":${window.endEpochMs},"summary":{"distanceM":${number(summary.distanceM)},"revolutions":${number(summary.revolutions)},"movingDurationSec":${number(summary.movingDurationSec)},"uncertainDurationSec":${number(summary.uncertainDurationSec)},"averageSpeedMS":${number(averageSpeedMS)},"maxSpeedMS":${number(summary.maxSpeedMS)},"sessionCount":${sessions.size},"longestSessionSec":${number(longestSessionSec)}},"live":$liveJson,"hourly":[$hourlyJson],"sessions":[$sessionsJson],"history":[$historyJson]}"""
    }

    private fun number(value: Double): String = if (value.isFinite()) {
        String.format(Locale.US, "%.6f", value)
    } else {
        "0.0"
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
        private const val HISTORY_NIGHTS = 7L
        private const val MAX_SESSIONS = 30

        private val dashboardHtml = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Hamster Wheel</title>
<style>
:root{color-scheme:dark;font-family:system-ui,-apple-system,sans-serif;background:#0b0d10;color:#eef2f6}
*{box-sizing:border-box}body{margin:0;padding:18px;max-width:980px;margin-inline:auto}h1{font-size:1.45rem;margin:0 0 4px}.muted{color:#9aa6b2}.live{display:flex;gap:8px;align-items:center;margin:10px 0 16px}.dot{width:10px;height:10px;border-radius:50%;background:#77808a}.dot.on{background:#45d483}.dot.warn{background:#f2b84b}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(145px,1fr));gap:10px}.card,section{background:#15191e;border:1px solid #252b33;border-radius:12px;padding:12px}.value{font-size:1.45rem;font-weight:700;margin-top:4px}section{margin-top:12px}section h2{font-size:1rem;margin:0 0 10px}.bars{display:flex;align-items:flex-end;gap:4px;height:150px;overflow-x:auto;padding-top:8px}.barwrap{min-width:28px;height:100%;display:flex;flex-direction:column;justify-content:flex-end;align-items:center}.bar{width:20px;min-height:2px;background:#72a7ff;border-radius:4px 4px 1px 1px}.label{font-size:10px;color:#8995a2;margin-top:5px}table{width:100%;border-collapse:collapse;font-size:.84rem}th,td{text-align:right;padding:7px 5px;border-bottom:1px solid #252b33}th:first-child,td:first-child{text-align:left}.warntext{color:#f2b84b}@media(max-width:520px){body{padding:12px}.cards{grid-template-columns:repeat(2,1fr)}.hide-small{display:none}}
</style>
</head>
<body>
<h1>Hamster Wheel Tracker</h1>
<div id="window" class="muted">Loading current reporting night…</div>
<div class="live"><span id="dot" class="dot"></span><strong id="liveState">Waiting for tracker</strong><span id="liveDetail" class="muted"></span></div>
<div class="cards">
  <div class="card"><div class="muted">Distance</div><div id="distance" class="value">—</div></div>
  <div class="card"><div class="muted">Moving time</div><div id="moving" class="value">—</div></div>
  <div class="card"><div class="muted">Revolutions</div><div id="revs" class="value">—</div></div>
  <div class="card"><div class="muted">Average speed</div><div id="avg" class="value">—</div></div>
  <div class="card"><div class="muted">Max speed</div><div id="max" class="value">—</div></div>
  <div class="card"><div class="muted">Sessions</div><div id="sessionsCount" class="value">—</div></div>
</div>
<section><h2>Hourly distance</h2><div id="bars" class="bars"></div></section>
<section><h2>Recent sessions</h2><div style="overflow-x:auto"><table><thead><tr><th>Start</th><th>Distance</th><th>Moving</th><th>Avg</th><th>Max</th></tr></thead><tbody id="sessions"></tbody></table></div></section>
<section><h2>Recent nights</h2><div style="overflow-x:auto"><table><thead><tr><th>Night</th><th>Distance</th><th>Moving</th><th>Sessions</th></tr></thead><tbody id="history"></tbody></table></div></section>
<div id="uncertain" class="muted" style="margin:14px 2px"></div>
<script>
const fmtTime=s=>{s=Math.max(0,s);const h=Math.floor(s/3600),m=Math.floor((s%3600)/60);return h>0?h+'h '+m+'m':m+'m'};
const fmtClock=ms=>new Date(ms).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
const fmtNight=ms=>new Date(ms).toLocaleDateString([], {month:'short',day:'numeric'});
const set=(id,v)=>document.getElementById(id).textContent=v;
async function refresh(){
 try{
  const r=await fetch('/api/dashboard',{cache:'no-store'});if(!r.ok)throw new Error('HTTP '+r.status);const d=await r.json(),s=d.summary;
  set('window',fmtNight(d.windowStartEpochMs)+' 18:00 → '+fmtNight(d.windowEndEpochMs)+' 18:00');
  set('distance',s.distanceM.toFixed(2)+' m');set('moving',fmtTime(s.movingDurationSec));set('revs',s.revolutions.toFixed(1));set('avg',s.averageSpeedMS.toFixed(3)+' m/s');set('max',s.maxSpeedMS.toFixed(3)+' m/s');set('sessionsCount',String(s.sessionCount));
  const dot=document.getElementById('dot');dot.className='dot';
  if(d.live){set('liveState',d.live.trackingState+(d.live.running?' · RUNNING':''));set('liveDetail',d.live.displaySpeedMS.toFixed(3)+' m/s · '+Math.round((Date.now()-d.live.updatedEpochMs)/1000)+'s ago');dot.classList.add(d.live.trackingState==='UNCERTAIN'?'warn':'on')}else{set('liveState','Waiting for tracker');set('liveDetail','')}
  const maxD=Math.max(0.001,...d.hourly.map(x=>x.distanceM));document.getElementById('bars').innerHTML=d.hourly.map(x=>'<div class="barwrap" title="'+x.distanceM.toFixed(2)+' m"><div class="bar" style="height:'+Math.max(2,130*x.distanceM/maxD)+'px"></div><div class="label">'+fmtClock(x.hourStartEpochMs)+'</div></div>').join('');
  document.getElementById('sessions').innerHTML=d.sessions.map(x=>'<tr><td>'+fmtClock(x.startEpochMs)+'</td><td>'+x.distanceM.toFixed(2)+' m</td><td>'+fmtTime(x.movingDurationSec)+'</td><td>'+x.averageSpeedMS.toFixed(3)+'</td><td>'+x.maxSpeedMS.toFixed(3)+'</td></tr>').join('')||'<tr><td colspan="5" class="muted">No completed sessions yet</td></tr>';
  document.getElementById('history').innerHTML=d.history.map(x=>'<tr><td>'+fmtNight(x.startEpochMs)+'</td><td>'+x.distanceM.toFixed(2)+' m</td><td>'+fmtTime(x.movingDurationSec)+'</td><td>'+x.sessionCount+'</td></tr>').join('');
  const u=s.uncertainDurationSec;document.getElementById('uncertain').className=u>1?'warntext':'muted';set('uncertain',u>1?'Uncertain tracking time: '+fmtTime(u)+' — ambiguous motion is not guessed.':'No significant uncertain interval recorded.');
 }catch(e){set('liveState','Dashboard connection error');set('liveDetail',String(e))}
}
refresh();setInterval(refresh,3000);
</script>
</body>
</html>
        """.trimIndent()
    }
}
