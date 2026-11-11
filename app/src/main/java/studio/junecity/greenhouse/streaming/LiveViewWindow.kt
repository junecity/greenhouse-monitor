package studio.junecity.greenhouse.streaming

/** All deadlines use elapsedRealtime so changing the phone clock cannot extend a view. */
internal data class LiveViewWindow(val phase: Phase = Phase.Idle, val deadlineMs: Long = 0) {
    enum class Phase { Idle, Connecting, Playing, Expired, Failed }

    val active get() = phase == Phase.Connecting || phase == Phase.Playing
    fun start(now: Long) = LiveViewWindow(Phase.Connecting, now + 30_000)
    fun frame(now: Long) = when {
        phase != Phase.Connecting -> this
        now >= deadlineMs -> LiveViewWindow(Phase.Failed)
        else -> LiveViewWindow(Phase.Playing, now + 15_000)
    }
    fun tick(now: Long) = when {
        !active || now < deadlineMs -> this
        phase == Phase.Playing -> LiveViewWindow(Phase.Expired)
        else -> LiveViewWindow(Phase.Failed)
    }
    fun secondsRemaining(now: Long) = ((deadlineMs - now).coerceAtLeast(0) + 999) / 1000
}
