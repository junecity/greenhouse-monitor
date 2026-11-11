package studio.junecity.greenhouse.streaming

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Prevents CameraX preview and the on-demand WebRTC capturer from opening the front camera together. */
object CloudCameraLease {
    val preview = VideoFrameOutput()
    private val mutableRemoteActive = MutableStateFlow(false)
    val remoteActive = mutableRemoteActive.asStateFlow()
    fun setRemoteActive(active: Boolean) { mutableRemoteActive.value = active }
}
