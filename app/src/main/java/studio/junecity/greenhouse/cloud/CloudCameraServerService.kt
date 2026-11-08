package studio.junecity.greenhouse.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private var worker: Job? = null
    private var currentPeer: LibWebRtcPeer? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Greenhouse remote camera", NotificationManager.IMPORTANCE_LOW)
        )
        showNotification("Waiting for an authorized viewer", cameraActive = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker?.isActive != true) worker = scope.launch { pollForSessions() }
        return START_STICKY
    }

    private fun showNotification(message: String, cameraActive: Boolean) {
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Greenhouse camera ready")
            .setContentText(message)
            .setOngoing(true).setOnlyAlertOnce(true).build()
        val type = when {
            cameraActive && Build.VERSION.SDK_INT >= 30 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private suspend fun pollForSessions() {
        val enrollment = SecureSettingsStore(this).cloudEnrollment()
        val configuration = runCatching { CloudRuntime.configurationOrNull() }.getOrNull()
        if (enrollment?.role != CloudDeviceRole.GreenhouseServer || configuration == null) {
            stopSelf(); return
        }
        val gateway = runCatching { gatewayForEnrollment(this, configuration, enrollment) }.getOrElse { stopSelf(); return }
        while (scope.isActive) {
            val pending = runCatching { gateway.pendingRemoteStreamingSession(enrollment.deviceId) }.getOrNull()
            if (pending == null) delay(POLL_INTERVAL_MS) else serve(gateway, pending)
        }
    }

    private suspend fun serve(gateway: AuthenticatedCloudGateway, session: RemoteStreamingSession) {
        CloudCameraLease.setRemoteActive(true)
        showNotification("Authorized remote viewer connecting", cameraActive = true)
        delay(500L)
        val profile = CameraStreamProfile.from(AppPreferencesStore(this).load().cameraStreamQuality)
        try {
            var attempt = 0
            var activeSession = session
            while (scope.isActive && attempt < MAX_ATTEMPTS && System.currentTimeMillis() < activeSession.expiresAtEpochMs) {
                val disconnected = CompletableDeferred<String>()
                val leaseJob = scope.launch {
                    while (isActive) {
                        delay(LEASE_RENEWAL_INTERVAL_MS)
                        if (runCatching { gateway.renewRemoteStreamingLease(activeSession.id) }.isFailure) {
                            disconnected.complete("authorization_lease_failed")
                            break
                        }
                    }
                }
                val peer = LibWebRtcPeer(this, WebRtcRole.Master, KinesisWebSocketSignaling(), object : WebRtcPeerEvents {
                    override fun onConnected() = showNotification("Encrypted live view active", cameraActive = true)
                    override fun onDisconnected(reason: String) { disconnected.complete(reason) }
                    override fun onRemoteVideo(track: VideoTrack) = Unit
                }, profile)
                currentPeer = peer
                try {
                    peer.connect(activeSession)
                    val remaining = (activeSession.expiresAtEpochMs - System.currentTimeMillis()).coerceAtLeast(1L)
                    if (withTimeoutOrNull(remaining) { disconnected.await() } == null) break
                } catch (_: Throwable) {
                    // A bounded retry below handles camera, signaling, and network-transition failures.
                } finally {
                    leaseJob.cancel()
                    peer.close()
                    if (currentPeer === peer) currentPeer = null
                }
                attempt++
                if (attempt < MAX_ATTEMPTS) {
                    delay((1_000L shl attempt.coerceAtMost(4)).coerceAtMost(15_000L))
                    activeSession = runCatching { gateway.refreshRemoteStreamingConnection(activeSession.id) }.getOrElse { break }
                }
            }
        } finally {
            withContext(NonCancellable) { runCatching { gateway.endRemoteStreamingSession(session.id) } }
            CloudCameraLease.setRemoteActive(false)
            showNotification("Waiting for an authorized viewer", cameraActive = false)
        }
    }

    override fun onDestroy() {
        currentPeer?.close()
        currentPeer = null
        CloudCameraLease.setRemoteActive(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "greenhouse_cloud_camera"
        private const val NOTIFICATION_ID = 4173
        private const val POLL_INTERVAL_MS = 5_000L
        private const val LEASE_RENEWAL_INTERVAL_MS = 60_000L
        private const val MAX_ATTEMPTS = 5
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, CloudCameraServerService::class.java)
            if (enabled) context.startForegroundService(intent) else context.stopService(intent)
        }
    }
}
