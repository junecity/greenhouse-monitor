package studio.junecity.greenhouse.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import studio.junecity.greenhouse.streaming.LiveViewWindow
import studio.junecity.greenhouse.streaming.LiveViewWindow.Phase

@Composable
internal fun TimedLiveView(
    modifier: Modifier,
    t: (Float) -> TextUnit,
    onStatus: (Boolean, String?) -> Unit,
    content: @Composable ((Boolean, String?) -> Unit) -> Unit
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val notify by rememberUpdatedState(onStatus)
    var window by remember { mutableStateOf(LiveViewWindow()) }
    var request by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var failure by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                window = LiveViewWindow()
                request++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(window.phase) {
        if (!window.active) {
            notify(false, when (window.phase) {
                Phase.Failed -> failure ?: "Could not connect to server camera"
                Phase.Expired -> "Live View ended"
                else -> "Ready for Live View"
            })
        }
        while (window.active) {
            now = SystemClock.elapsedRealtime()
            window = window.tick(now)
            delay(100L)
        }
    }

    fun start() {
        failure = null
        now = SystemClock.elapsedRealtime()
        request++
        window = window.start(now)
        notify(false, "Connecting to greenhouse…")
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (window.active) {
            key(request) {
                val thisRequest = request
                content { live, error ->
                    if (request == thisRequest && window.active) {
                        if (live) window = window.frame(SystemClock.elapsedRealtime())
                        else if (error != null) {
                            failure = error
                            window = LiveViewWindow(Phase.Failed)
                        }
                        notify(live, error)
                    }
                }
            }
            Button(
                onClick = { window = LiveViewWindow() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xEE292929), contentColor = Color.White)
            ) {
                Text(if (window.phase == Phase.Playing) "Stop · ${window.secondsRemaining(now)}s" else "Stop", fontSize = t(14f))
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (window.phase == Phase.Failed) Text(failure ?: "Could not connect to server camera", color = Color.White, fontSize = t(14f))
                if (window.phase == Phase.Expired) Text("Continue live viewing?", color = Color.White, fontSize = t(15f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = ::start, colors = ButtonDefaults.buttonColors(containerColor = DashboardTokens.Amber, contentColor = Color.Black)) {
                        Text(if (window.phase == Phase.Expired) "Continue · 15s" else "Live View · 15s", fontSize = t(16f))
                    }
                    if (window.phase == Phase.Expired) {
                        androidx.compose.material3.TextButton(onClick = { window = LiveViewWindow() }) {
                            Text("Done", color = Color.White, fontSize = t(14f))
                        }
                    }
                }
            }
        }
    }
}

/** A tap authorizes one foreground session. Coming back requires another tap. */
@Composable
internal fun LiveViewEffect(key: Any?, action: suspend CoroutineScope.() -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val operation by rememberUpdatedState(action)
    LaunchedEffect(key, lifecycle) {
        var started = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!started) {
                started = true
                operation()
            }
        }
    }
}
