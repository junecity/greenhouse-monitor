package studio.junecity.greenhouse.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import studio.junecity.greenhouse.camera.CameraCrypto
import studio.junecity.greenhouse.camera.CameraPairing
import studio.junecity.greenhouse.camera.CameraPairingStore
import studio.junecity.greenhouse.cloud.CloudDeviceRole
import studio.junecity.greenhouse.cloud.CloudRuntime
import studio.junecity.greenhouse.settings.SecureSettingsStore
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun RemoteCameraFeed(
    d: (Float) -> androidx.compose.ui.unit.Dp,
    t: (Float) -> androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    onStatus: (Boolean, String?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val configuration = remember { runCatching { CloudRuntime.configurationOrNull() }.getOrNull() }
    var checkout by remember { mutableStateOf<studio.junecity.greenhouse.cloud.AuthenticatedCloudGateway?>(null) }
    checkout?.let { VideoSubscriptionDialog(it) { checkout = null } }
    TimedLiveView(modifier, t, onStatus) { report ->
        // Resolve the current greenhouse when the viewer requests video after a switch or re-pair.
        val enrollment = remember { SecureSettingsStore(context).cloudEnrollment() }
        if (configuration != null) {
            if (enrollment?.role == CloudDeviceRole.ViewerClient && enrollment.greenhouseId.isNotBlank()) {
                CloudWebRtcFeed(enrollment, configuration, d, t, Modifier.fillMaxSize(), report) { checkout = it }
            } else {
                LaunchedEffect(Unit) { report(false, "Pair a greenhouse server to view its camera") }
            }
        } else {
            LegacyRemoteCameraFeed(d, t, Modifier.fillMaxSize(), report)
        }
    }
}

@Composable
private fun LegacyRemoteCameraFeed(
    d: (Float) -> androidx.compose.ui.unit.Dp,
    t: (Float) -> androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    onStatus: (Boolean, String?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val pairing = remember { CameraPairingStore(context).load() }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var preferredHost by remember { mutableStateOf(pairing?.localHost) }
    var message by remember { mutableStateOf(if (pairing == null) "Pair this viewer in Settings" else "Connecting securely…") }
    var connected by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val previewAlpha by androidx.compose.animation.core.animateFloatAsState(if(connected)1f else .25f,androidx.compose.animation.core.tween(accessibleMotionDuration(AppMotion.CameraConnectionFadeMs)),label="remote camera connection")

    LiveViewEffect(pairing) {
        if (pairing == null) {
            onStatus(false, "Pair this viewer in Settings")
            return@LiveViewEffect
        }
        while (true) {
            val result = withContext(Dispatchers.IO) { fetchFrame(pairing, preferredHost) }
            result.onSuccess {
                bitmap = it.bitmap
                preferredHost = it.host
                connected = true
                message = "Encrypted live view"
                onStatus(true, null)
            }.onFailure {
                connected = false
                message = it.message ?: "Camera unavailable"
                onStatus(false, message)
            }
            delay(if (connected) 220 else 1_500)
        }
    }

    Box(modifier.clip(RoundedCornerShape(d(22f))).background(Color.Black)
        .onSizeChanged{viewport=it}
        .pointerInput(Unit){detectTransformGestures{_,drag,scaleChange,_->
            val nextZoom=(zoom*scaleChange).coerceIn(1f,5f)
            val maxX=(nextZoom-1f)*viewport.width/2f
            val maxY=(nextZoom-1f)*viewport.height/2f
            pan=if(nextZoom==1f)Offset.Zero else Offset((pan.x+drag.x).coerceIn(-maxX,maxX),(pan.y+drag.y).coerceIn(-maxY,maxY))
            zoom=nextZoom
        }}
        .pointerInput(Unit){detectTapGestures(onDoubleTap={zoom=1f;pan=Offset.Zero})}, contentAlignment = Alignment.Center) {
        bitmap?.let { frame ->
            Image(frame.asImageBitmap(), null, Modifier.fillMaxSize().alpha(previewAlpha).graphicsLayer{scaleX=zoom;scaleY=zoom;translationX=pan.x;translationY=pan.y}, contentScale = ContentScale.Crop)
        } ?: Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DashboardIconView(DashboardIcon.Camera, DashboardTokens.Amber, Modifier.size(d(54f)))
            Text(message, color = Color.White, fontSize = t(14f), fontWeight = FontWeight.Bold)
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
        if(connected)Text(if(zoom>1f)"${"%.1f".format(zoom)}× · drag to pan · double-tap to reset" else "Pinch to zoom · drag to pan",color=Color.White,fontSize=t(9f),fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.BottomCenter).padding(d(10f)).background(Color(0xB0101113),RoundedCornerShape(d(16f))).padding(horizontal=d(12f),vertical=d(6f)))
    }
}

private data class RemoteFrame(val bitmap: Bitmap, val host: String)

private fun fetchFrame(pairing: CameraPairing, preferredHost: String?): Result<RemoteFrame> {
    val hosts = listOfNotNull(preferredHost, pairing.localHost, pairing.publicHost).distinct()
    var last: Throwable = IllegalStateException("No camera address")
    for (host in hosts) {
        try {
            val connection = URL(pairing.endpoint(host) + "&n=${System.nanoTime()}").openConnection() as HttpURLConnection
            connection.connectTimeout = if (host == pairing.localHost) 1_000 else 3_000
            connection.readTimeout = 4_000
            connection.useCaches = false
            if (connection.responseCode != 200) error("Camera returned ${connection.responseCode}")
            val packet = connection.inputStream.use { it.readBytes() }
            val jpeg = CameraCrypto.decrypt(packet, pairing)
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("Invalid camera image")
            return Result.success(RemoteFrame(bitmap, host))
        } catch (error: Throwable) {
            last = error
        }
    }
    return Result.failure(last)
}
