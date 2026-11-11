package studio.junecity.greenhouse.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import android.os.SystemClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.webrtc.VideoTrack
import studio.junecity.greenhouse.camera.CameraStreamProfile
import studio.junecity.greenhouse.settings.AppPreferencesStore
import studio.junecity.greenhouse.settings.SecureSettingsStore
import studio.junecity.greenhouse.streaming.CloudCameraLease
import studio.junecity.greenhouse.streaming.KinesisWebSocketSignaling
import studio.junecity.greenhouse.streaming.LibWebRtcPeer
import studio.junecity.greenhouse.streaming.WebRtcPeerEvents
import studio.junecity.greenhouse.streaming.WebRtcRole

/** Polls only for authorized, on-demand sessions; the camera opens only while a viewer is connected. */
class CloudCameraServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = EnrollmentBoundWorker(scope)

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Greenhouse remote camera", NotificationManager.IMPORTANCE_LOW)
        )
        showNotification("Waiting for an authorized viewer", cameraActive = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        worker.start(SecureSettingsStore(this).cloudEnrollment()) { pollForSessions() }
        return START_STICKY
    }

    private fun showNotification(message: String, cameraActive: Boolean) {
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Greenhouse camera ready")
            .setContentText(message)
            .setOngoing(true).setOnlyAlertOnce(true).build()
        // Establish the camera foreground-service capability while the server app is visible.
        // Android 14+ does not allow a background service to add the camera type later when a
        // remote viewer arrives. The camera hardware still remains closed until serve().
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private suspend fun pollForSessions() {
        val enrollment = SecureSettingsStore(this).cloudEnrollment()
        val configuration = runCatching { CloudRuntime.configurationOrNull() }.getOrNull()
        if (enrollment?.role != CloudDeviceRole.GreenhouseServer || configuration == null) {
            Log.w(TAG, "Camera server stopped: enrollment or cloud configuration unavailable")
            stopSelf(); return
        }
        val gateway = runCatching { gatewayForEnrollment(this, configuration, enrollment) }.getOrElse {
            Log.e(TAG, "Camera server authentication failed", it)
            stopSelf(); return
        }
        Log.i(TAG, "Camera server ready for authorized on-demand sessions")
        while (scope.isActive) {
            val pending = runCatching { gateway.pendingRemoteStreamingSession(enrollment.deviceId) }
                .onFailure { Log.w(TAG, "Unable to poll for a camera session", it) }
                .getOrNull()
            if (pending == null) {
                delay(POLL_INTERVAL_MS)
            } else {
                try {
                    cameraSessionLock.withLock { serve(gateway, pending) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Camera session ended before it could serve the viewer", error)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun serve(gateway: AuthenticatedCloudGateway, session: RemoteStreamingSession) {
        CloudCameraLease.setRemoteActive(true)
        try {
            showNotification("Authorized remote viewer connecting", cameraActive = true)
            Log.i(TAG, "Starting authorized camera session ${session.id.take(8)}")
            delay(500L)
            val profile = CameraStreamProfile.from(AppPreferencesStore(this).load().cameraStreamQuality)
            var attempt = 0
            var activeSession = session
            var deadline = SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MS
            var firstConnection = true
            while (scope.isActive && SystemClock.elapsedRealtime() < deadline && attempt < MAX_ATTEMPTS && System.currentTimeMillis() < activeSession.expiresAtEpochMs) {
                val disconnected = CompletableDeferred<String>()
                val connectionReady = CompletableDeferred<Unit>()
                val peer = LibWebRtcPeer(this, WebRtcRole.Master, KinesisWebSocketSignaling(), object : WebRtcPeerEvents {
                    override fun onConnected() {
                        connectionReady.complete(Unit)
                        Log.i(TAG, "Camera session ${activeSession.id.take(8)} connected")
                        showNotification("Encrypted live view active", cameraActive = true)
                    }
                    override fun onDisconnected(reason: String) {
                        Log.w(TAG, "Camera session ${activeSession.id.take(8)} disconnected: $reason")
                        disconnected.complete(reason)
                    }
                    override fun onRemoteVideo(track: VideoTrack) = Unit
                }, profile, localPreview = CloudCameraLease.preview)
                val leaseJob = scope.launch {
                    while (isActive) {
                        delay(LEASE_RENEWAL_INTERVAL_MS)
                        if (runCatching { gateway.renewRemoteStreamingLease(activeSession.id) }.isFailure) {
                            disconnected.complete("authorization_lease_failed")
                            break
                        }
                    }
                }
                try {
                    peer.connect(activeSession)
                    val remaining = (activeSession.expiresAtEpochMs - System.currentTimeMillis()).coerceAtLeast(1L)
                    val established = withTimeoutOrNull((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)) {
                        select {
                            connectionReady.onAwait { true }
                            disconnected.onAwait { false }
                        }
                    } ?: false
                    if (!established) disconnected.complete("connection_timeout")
                    // Release abandoned capture even if a vanishing viewer cannot deliver DELETE.
                    if (established && firstConnection) {
                        firstConnection = false
                        deadline = SystemClock.elapsedRealtime() + SERVER_VIEW_LIMIT_MS
                    }
                    val mediaRemaining = minOf(remaining, deadline - SystemClock.elapsedRealtime())
                    if (established && withTimeoutOrNull(mediaRemaining) { disconnected.await() } == null) break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "Camera session ${activeSession.id.take(8)} failed", error)
                    // A bounded retry below handles camera, signaling, and network-transition failures.
                } finally {
                    leaseJob.cancel()
                    peer.close()
                }
                if (disconnected.isCompleted && disconnected.await() == "authorization_lease_failed") break
                attempt++
                if (attempt < MAX_ATTEMPTS) {
                    delay((1_000L shl attempt.coerceAtMost(4)).coerceAtMost(15_000L))
                    activeSession = runCatching { gateway.refreshRemoteStreamingConnection(activeSession.id) }.getOrElse { break }
                }
            }
        } finally {
            CloudCameraLease.setRemoteActive(false)
            withContext(NonCancellable) { runCatching { gateway.endRemoteStreamingSession(session.id) } }
            Log.i(TAG, "Camera session ${session.id.take(8)} ended")
            if (scope.isActive) showNotification("Waiting for an authorized viewer", cameraActive = false)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val cameraSessionLock = Mutex()
        private const val CHANNEL = "greenhouse_cloud_camera"
        private const val NOTIFICATION_ID = 4173
        private const val POLL_INTERVAL_MS = 5_000L
        // Detect a viewer replacement or abandoned authorization quickly instead of holding the
        // camera on an obsolete session for the full two-minute authorization lease.
        private const val LEASE_RENEWAL_INTERVAL_MS = 10_000L
        private const val SERVER_VIEW_LIMIT_MS = 20_000L // 15 seconds of playback plus first-frame latency
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val MAX_ATTEMPTS = 5
        private const val TAG = "GreenhouseCamera"
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, CloudCameraServerService::class.java)
            if (enabled) context.startForegroundService(intent) else context.stopService(intent)
        }
    }
}
