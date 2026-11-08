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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val callbackScope = rememberCoroutineScope()
    val authResult = remember(configuration) { runCatching { CognitoAuthManager(context, configuration).also(CognitoAuthManager::initialize) } }
    val gateway = remember(authResult, enrollment.deviceId) { authResult.getOrNull()?.gateway(enrollment.deviceId) }
    var remoteTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var connected by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(if (authResult.isFailure) "Secure live view unavailable" else "Authorizing secure live view…") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    val egl = remember { EglBase.create() }
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
        }
    }
    DisposableEffect(Unit) { onDispose { renderer.release(); egl.release() } }
    DisposableEffect(remoteTrack) {
        remoteTrack?.addSink(renderer)
        onDispose { remoteTrack?.removeSink(renderer) }
    }

    LaunchedEffect(gateway, enrollment) {
        val api = gateway ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            connected = false
            remoteTrack = null
            message = "Authorizing secure live view…"
            val sessionResult = runCatching {
                api.createRemoteStreamingSession(enrollment.greenhouseId, enrollment.deviceId)
            }
            val session = sessionResult.getOrElse { error ->
                message = when (error) {
                    is CloudApiException -> when (error.message) {
                        "premium_required", "inactive", "expired", "revoked" -> "Premium remote camera access required"
                        else -> "Remote camera unavailable"
                    }
                    else -> "Remote camera unavailable"
                }
                onStatus(false, message)
                return@repeatOnLifecycle
            }
            try {
                var attempt = 0
                var activeSession = session
                while (attempt < 5 && System.currentTimeMillis() < activeSession.expiresAtEpochMs) {
                    val disconnected = CompletableDeferred<String>()
                    val leaseJob = launch {
                        while (true) {
                            delay(60_000L)
                            if (runCatching { api.renewRemoteStreamingLease(activeSession.id) }.isFailure) {
                                disconnected.complete("authorization_lease_failed")
                                break
                            }
                        }
                    }
                    val peer = LibWebRtcPeer(context, WebRtcRole.Viewer, KinesisWebSocketSignaling(), object : WebRtcPeerEvents {
                        override fun onConnected() {
                            callbackScope.launch {
                                connected = true
                                message = "Secure junecity.studio live view"
                                onStatus(true, null)
                            }
                        }
                        override fun onDisconnected(reason: String) { disconnected.complete(reason) }
                        override fun onRemoteVideo(track: VideoTrack) { callbackScope.launch { remoteTrack = track } }
                    })
                    try {
                        peer.connect(activeSession)
                        val remaining = (activeSession.expiresAtEpochMs - System.currentTimeMillis()).coerceAtLeast(1L)
                        if (withTimeoutOrNull(remaining) { disconnected.await() } == null) break
                    } finally {
                        leaseJob.cancel()
                        peer.close()
                        connected = false
                        remoteTrack = null
                    }
                    attempt++
                    if (attempt < 5) {
                        message = "Reconnecting secure live view…"
                        delay((1_000L shl attempt.coerceAtMost(4)).coerceAtMost(15_000L))
                        activeSession = runCatching { api.refreshRemoteStreamingConnection(activeSession.id) }.getOrElse { break }
                    }
                }
                if (!connected) {
                    message = "Live session ended"
                    onStatus(false, message)
                }
            } finally {
                withContext(NonCancellable) { runCatching { api.endRemoteStreamingSession(session.id) } }
                connected = false
                remoteTrack = null
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
        if (remoteTrack != null) {
            AndroidView(
                factory = { renderer },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
                }
            )
        } else {
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
        if (connected) Text(
            if (zoom > 1f) "${"%.1f".format(zoom)}× · drag to pan · double-tap to reset" else "Pinch to zoom · drag to pan",
            color = Color.White, fontSize = t(9f), fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(d(10f))
                .background(Color(0xB0101113), RoundedCornerShape(d(16f))).padding(horizontal = d(12f), vertical = d(6f))
        )
    }
}
