package studio.junecity.greenhouse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import studio.junecity.greenhouse.streaming.VideoFrameOutput
import org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.webrtc.VideoTrack
import studio.junecity.greenhouse.cloud.CloudApiException
import studio.junecity.greenhouse.cloud.CognitoAuthManager
import studio.junecity.greenhouse.cloud.CognitoConfiguration
import studio.junecity.greenhouse.settings.CloudEnrollment
import studio.junecity.greenhouse.streaming.KinesisWebSocketSignaling
import studio.junecity.greenhouse.streaming.LibWebRtcPeer
import studio.junecity.greenhouse.streaming.WebRtcPeerEvents
import studio.junecity.greenhouse.streaming.WebRtcRole

@Composable
fun CloudWebRtcFeed(
    enrollment: CloudEnrollment,
    configuration: CognitoConfiguration,
    d: (Float) -> androidx.compose.ui.unit.Dp,
    t: (Float) -> androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    onStatus: (Boolean, String?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val callbackScope = rememberCoroutineScope()
    val authResult = remember(configuration) { runCatching { CognitoAuthManager(context, configuration).also(CognitoAuthManager::initialize) } }
    val gateway = remember(authResult, enrollment.deviceId) { authResult.getOrNull()?.gateway(enrollment.deviceId) }
    val output = remember { VideoFrameOutput() }
    val report by androidx.compose.runtime.rememberUpdatedState(onStatus)
    var connected by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(if (authResult.isFailure) "Secure live view unavailable" else "Authorizing secure live view…") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    LiveViewEffect(gateway) {
        val api = gateway ?: run {
            report(false, "Secure live view unavailable")
            return@LiveViewEffect
        }
        // A fast stop/start must finish closing the previous backend session first.
        viewerSessionLock.withLock {
            connected = false
            message = "Authorizing secure live view…"
            val sessionResult = runCatching {
                withContext(NonCancellable) { api.createRemoteStreamingSession(enrollment.greenhouseId, enrollment.deviceId) }
            }
            val session = sessionResult.getOrElse { error ->
                message = when (error) {
                    is CloudApiException -> when (error.message) {
                        "premium_required", "inactive", "expired", "revoked" -> "Remote camera access is not enabled for this account"
                        else -> "Remote camera unavailable"
                    }
                    else -> "Remote camera unavailable"
                }
                report(false, message)
                return@withLock
            }
            try {
                currentCoroutineContext().ensureActive()
                var attempt = 0
                var activeSession = session
                while (attempt < 5 && System.currentTimeMillis() < activeSession.expiresAtEpochMs) {
                    val disconnected = CompletableDeferred<String>()
                    val connectionReady = CompletableDeferred<Unit>()
                    val receiving = AtomicBoolean(true)
                    val firstFrame = AtomicBoolean(true)
                    val sink = VideoSink { frame ->
                        if (receiving.get()) {
                            output.onFrame(frame)
                            if (firstFrame.compareAndSet(true, false)) callbackScope.launch {
                                if (receiving.get()) {
                                    connected = true
                                    message = "Live"
                                    report(true, null)
                                }
                            }
                        }
                    }
                    val peer = LibWebRtcPeer(context, WebRtcRole.Viewer, KinesisWebSocketSignaling(), object : WebRtcPeerEvents {
                        override fun onConnected() { connectionReady.complete(Unit) }
                        override fun onDisconnected(reason: String) { disconnected.complete(reason) }
                        override fun onRemoteVideo(track: VideoTrack) { track.addSink(sink) }
                    })
                    try {
                        peer.connect(activeSession)
                        val remaining = (activeSession.expiresAtEpochMs - System.currentTimeMillis()).coerceAtLeast(1L)
                        val established = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                            select {
                                connectionReady.onAwait { true }
                                disconnected.onAwait { false }
                            }
                        } ?: false
                        if (!established) disconnected.complete("connection_timeout")
                        if (established && withTimeoutOrNull(remaining) { disconnected.await() } == null) break
                    } finally {
                        receiving.set(false)
                        peer.close()
                        connected = false
                    }
                    attempt++
                    if (attempt < 5) {
                        message = "Reconnecting secure live view…"
                        delay((1_000L shl attempt.coerceAtMost(4)).coerceAtMost(15_000L))
                        activeSession = runCatching { api.refreshRemoteStreamingConnection(activeSession.id) }.getOrElse { break }
                    }
                }
                if (!connected) {
                    message = "Unable to connect to server camera"
                    report(false, message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                report(false, "Unable to open server camera")
            } finally {
                withContext(NonCancellable) { runCatching { api.endRemoteStreamingSession(session.id) } }
                connected = false
            }
        }
    }

    Box(
        modifier.clip(RoundedCornerShape(d(22f))).background(Color.Black)
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, drag, scaleChange, _ ->
                    val nextZoom = (zoom * scaleChange).coerceIn(1f, 5f)
                    val maxX = (nextZoom - 1f) * viewport.width / 2f
                    val maxY = (nextZoom - 1f) * viewport.height / 2f
                    pan = if (nextZoom == 1f) Offset.Zero else Offset(
                        (pan.x + drag.x).coerceIn(-maxX, maxX),
                        (pan.y + drag.y).coerceIn(-maxY, maxY)
                    )
                    zoom = nextZoom
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero }) },
        contentAlignment = Alignment.Center
    ) {
        PeerVideoSurface(output, Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
        })
        if (!connected) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DashboardIconView(DashboardIcon.Camera, DashboardTokens.Amber, Modifier.size(d(54f)))
                Text(message, color = Color.White, fontSize = t(14f), fontWeight = FontWeight.Bold)
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(d(14f))
                .background(Color(0xD9101113), RoundedCornerShape(d(20f)))
                .padding(horizontal = d(14f), vertical = d(7f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(d(9f)).background(if (connected) DashboardTokens.Green else DashboardTokens.Amber, CircleShape))
            Spacer(Modifier.width(d(8f)))
            Text(message, color = Color.White, fontSize = t(11f), fontWeight = FontWeight.Bold)
        }

    }
}

private val viewerSessionLock = Mutex()
private const val CONNECTION_TIMEOUT_MS = 30_000L
