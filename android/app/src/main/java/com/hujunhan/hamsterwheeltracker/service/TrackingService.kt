package com.hujunhan.hamsterwheeltracker.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.hujunhan.hamsterwheeltracker.MainActivity
import com.hujunhan.hamsterwheeltracker.camera.AnalysisStats
import com.hujunhan.hamsterwheeltracker.camera.CameraFrameAnalyzer
import com.hujunhan.hamsterwheeltracker.persistence.TrackingDatabase
import com.hujunhan.hamsterwheeltracker.persistence.TrackingRecorder
import com.hujunhan.hamsterwheeltracker.tracking.TrackerSnapshot
import com.hujunhan.hamsterwheeltracker.vision.CalibrationConfig
import com.hujunhan.hamsterwheeltracker.vision.CalibrationStore
import com.hujunhan.hamsterwheeltracker.vision.HsvSample
import com.hujunhan.hamsterwheeltracker.vision.MarkerFrameResult
import com.hujunhan.hamsterwheeltracker.web.DashboardServer
import com.hujunhan.hamsterwheeltracker.web.LanAddress
import fi.iki.elonen.NanoHTTPD
import org.opencv.android.OpenCVLoader
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the long-running camera analysis pipeline.
 *
 * The service is both started and bound: the started foreground-service lifetime
 * keeps tracking alive when MainActivity is stopped/screen-off, while the local
 * binder gives the visible UI live diagnostics and calibration controls.
 */
class TrackingService : LifecycleService() {
    interface Listener {
        fun onServiceState(state: ServiceState) = Unit
        fun onStats(snapshot: AnalysisStats.Snapshot) = Unit
        fun onMarkerFrame(result: MarkerFrameResult) = Unit
        fun onTrackerSnapshot(snapshot: TrackerSnapshot) = Unit
        fun onHsvSample(sample: HsvSample) = Unit
        fun onVisionError(message: String) = Unit
    }

    data class ServiceState(
        val tracking: Boolean,
        val analysisEnabled: Boolean,
        val message: String,
        val dashboardUrl: String?,
    )

    inner class LocalBinder : Binder() {
        fun service(): TrackingService = this@TrackingService
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<Listener>()

    private lateinit var analysisExecutor: ExecutorService
    private lateinit var frameAnalyzer: CameraFrameAnalyzer
    private lateinit var calibrationStore: CalibrationStore
    private lateinit var trackingRecorder: TrackingRecorder

    private var dashboardServer: DashboardServer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var calibration = CalibrationConfig()

    @Volatile
    private var tracking = false

    @Volatile
    private var analysisEnabled = true

    @Volatile
    private var serviceMessage = "Starting tracking service…"

    @Volatile
    private var dashboardUrl: String? = null

    @Volatile
    private var latestStats: AnalysisStats.Snapshot? = null

    @Volatile
    private var latestMarkerFrame: MarkerFrameResult? = null

    @Volatile
    private var latestTrackerSnapshot: TrackerSnapshot? = null

    private var lastNotificationUpdateMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting wheel tracker…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopTracking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startTracking()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onServiceState(currentState())
        latestStats?.let(listener::onStats)
        latestMarkerFrame?.let(listener::onMarkerFrame)
        latestTrackerSnapshot?.let(listener::onTrackerSnapshot)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun currentState(): ServiceState = ServiceState(
        tracking = tracking,
        analysisEnabled = analysisEnabled,
        message = serviceMessage,
        dashboardUrl = dashboardUrl,
    )

    fun currentCalibration(): CalibrationConfig = calibration

    fun updateCalibration(value: CalibrationConfig) {
        calibration = value
        calibrationStore.save(value)
        if (::frameAnalyzer.isInitialized) frameAnalyzer.setCalibration(value)
    }

    fun requestHsvSample(xPx: Float, yPx: Float) {
        if (::frameAnalyzer.isInitialized) frameAnalyzer.requestHsvSample(xPx, yPx)
    }

    fun setAnalysisEnabled(enabled: Boolean) {
        analysisEnabled = enabled
        if (::frameAnalyzer.isInitialized) frameAnalyzer.setEnabled(enabled)
        serviceMessage = if (enabled) "Tracking active" else "Tracking service active; analysis paused"
        publishState()
        updateNotification(force = true)
    }

    private fun startTracking() {
        if (tracking) {
            publishState()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            serviceMessage = "Camera permission required; open the app to grant access"
            publishState()
            updateNotification(force = true)
            return
        }

        val openCvReady = runCatching { OpenCVLoader.initDebug() }.getOrDefault(false)
        if (!openCvReady) {
            serviceMessage = "OpenCV initialization failed"
            publishState()
            updateNotification(force = true)
            return
        }

        calibrationStore = CalibrationStore(this)
        calibration = calibrationStore.load()
        analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "camera-analysis")
        }

        val database = TrackingDatabase.get(applicationContext)
        trackingRecorder = TrackingRecorder(database.trackingDao())
        startDashboard(database.trackingDao())

        frameAnalyzer = CameraFrameAnalyzer(
            initialCalibration = calibration,
            onStats = { snapshot ->
                latestStats = snapshot
                listeners.forEach { it.onStats(snapshot) }
            },
            onMarkerFrame = { result ->
                latestMarkerFrame = result
                listeners.forEach { it.onMarkerFrame(result) }
            },
            onTrackerSnapshot = { snapshot ->
                latestTrackerSnapshot = snapshot
                trackingRecorder.record(snapshot)
                listeners.forEach { it.onTrackerSnapshot(snapshot) }
                updateNotification(snapshot = snapshot)
            },
            onHsvSample = { sample -> listeners.forEach { it.onHsvSample(sample) } },
            onVisionError = { message ->
                serviceMessage = "Vision error: $message"
                listeners.forEach { it.onVisionError(message) }
                publishState()
            },
        )
        frameAnalyzer.setEnabled(analysisEnabled)

        acquireWakeLock()
        tracking = true
        serviceMessage = "Opening rear camera…"
        publishState()
        bindAnalysisCamera()
    }

    private fun bindAnalysisCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                runCatching {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, frameAnalyzer) }
                    analysisUseCase = analysis
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        analysis,
                    )
                    serviceMessage = "Tracking active · camera analysis running"
                    publishState()
                    updateNotification(force = true)
                }.onFailure { error ->
                    serviceMessage = "Camera analysis failed: ${error.message ?: error.javaClass.simpleName}"
                    publishState()
                    updateNotification(force = true)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun startDashboard(dao: com.hujunhan.hamsterwheeltracker.persistence.TrackingDao) {
        val server = DashboardServer(
            dao = dao,
            liveProvider = { trackingRecorder.latest() },
        )
        dashboardServer = server
        val error = runCatching {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }.exceptionOrNull()
        dashboardUrl = if (error == null) LanAddress.dashboardUrl(DashboardServer.DEFAULT_PORT) else null
        if (error != null) {
            serviceMessage = "Dashboard failed: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:wheel-tracking",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopTracking() {
        if (!tracking && !::analysisExecutor.isInitialized) return
        tracking = false
        serviceMessage = "Tracking stopped"
        analysisUseCase?.let { useCase -> cameraProvider?.unbind(useCase) }
        analysisUseCase = null
        dashboardServer?.stop()
        dashboardServer = null
        dashboardUrl = null
        if (::trackingRecorder.isInitialized) trackingRecorder.close()
        if (::frameAnalyzer.isInitialized) frameAnalyzer.close()
        if (::analysisExecutor.isInitialized) analysisExecutor.shutdown()
        releaseWakeLock()
        publishState()
    }

    private fun publishState() {
        val state = currentState()
        listeners.forEach { it.onServiceState(state) }
    }

    private fun updateNotification(snapshot: TrackerSnapshot? = latestTrackerSnapshot, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationUpdateMs < 2_000L) return
        lastNotificationUpdateMs = now
        val text = when {
            !tracking -> serviceMessage
            !analysisEnabled -> "Analysis paused"
            snapshot == null -> serviceMessage
            else -> String.format(
                Locale.US,
                "%.2f m · %.2f rev · %s",
                snapshot.totalDistanceM,
                snapshot.equivalentRevolutions,
                snapshot.trackingState.name,
            )
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(text),
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Wheel tracking",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps hamster-wheel camera tracking active"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Hamster wheel tracking")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(tracking)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "wheel_tracking"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.hujunhan.hamsterwheeltracker.action.START_TRACKING"
        const val ACTION_STOP = "com.hujunhan.hamsterwheeltracker.action.STOP_TRACKING"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
