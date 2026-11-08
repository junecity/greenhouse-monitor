package studio.junecity.greenhouse.streaming

import studio.junecity.greenhouse.cloud.RemoteStreamingSession

sealed interface StreamingState {
    data object Idle : StreamingState
    data object Authorizing : StreamingState
    data class Connecting(val session: RemoteStreamingSession, val attempt: Int) : StreamingState
    data class Connected(val session: RemoteStreamingSession) : StreamingState
    data class WaitingForNetwork(val session: RemoteStreamingSession, val nextAttempt: Int) : StreamingState
    data class BackingOff(val session: RemoteStreamingSession, val nextAttempt: Int) : StreamingState
    data object Stopped : StreamingState
    data class Failed(val reason: String) : StreamingState
}

sealed interface StreamingEvent {
    data object ViewerBecameVisible : StreamingEvent
    data class AuthorizationGranted(val session: RemoteStreamingSession) : StreamingEvent
    data class AuthorizationDenied(val reason: String) : StreamingEvent
    data object PeerConnected : StreamingEvent
    data object PeerDisconnected : StreamingEvent
    data object NetworkLost : StreamingEvent
    data object NetworkAvailable : StreamingEvent
    data object RetryElapsed : StreamingEvent
    data object ViewerHidden : StreamingEvent
    data object ClientPhoneLocked : StreamingEvent
    data object SessionExpired : StreamingEvent
}

sealed interface StreamingAction {
    data object RequestAuthorization : StreamingAction
    data class ConnectPeer(val session: RemoteStreamingSession) : StreamingAction
    data object DisconnectPeer : StreamingAction
    data class ScheduleReconnect(val attempt: Int, val delayMs: Long) : StreamingAction
    data class EndBackendSession(val sessionId: String) : StreamingAction
}

data class StreamingTransition(val state: StreamingState, val actions: List<StreamingAction> = emptyList())

class StreamingLifecycle(private val maximumReconnectAttempts: Int = 5) {
    var state: StreamingState = StreamingState.Idle
        private set

    fun accept(event: StreamingEvent): StreamingTransition {
        val transition = reduce(state, event)
        state = transition.state
        return transition
    }

    private fun reduce(current: StreamingState, event: StreamingEvent): StreamingTransition = when (event) {
        StreamingEvent.ViewerBecameVisible -> if (current is StreamingState.Idle || current is StreamingState.Stopped || current is StreamingState.Failed) {
            StreamingTransition(StreamingState.Authorizing, listOf(StreamingAction.RequestAuthorization))
        } else StreamingTransition(current)
        is StreamingEvent.AuthorizationGranted -> if (current is StreamingState.Authorizing) {
            StreamingTransition(StreamingState.Connecting(event.session, 0), listOf(StreamingAction.ConnectPeer(event.session)))
        } else StreamingTransition(current)
        is StreamingEvent.AuthorizationDenied -> if (current is StreamingState.Authorizing) StreamingTransition(StreamingState.Failed(event.reason)) else StreamingTransition(current)
        StreamingEvent.PeerConnected -> if (current is StreamingState.Connecting) StreamingTransition(StreamingState.Connected(current.session)) else StreamingTransition(current)
        StreamingEvent.PeerDisconnected -> when (current) {
            is StreamingState.Connected -> reconnect(current.session, 1)
            is StreamingState.Connecting -> reconnect(current.session, current.attempt + 1)
            else -> StreamingTransition(current)
        }
        StreamingEvent.NetworkLost -> when (current) {
            is StreamingState.Connected -> StreamingTransition(StreamingState.WaitingForNetwork(current.session, 1), listOf(StreamingAction.DisconnectPeer))
            is StreamingState.Connecting -> StreamingTransition(StreamingState.WaitingForNetwork(current.session, current.attempt + 1), listOf(StreamingAction.DisconnectPeer))
            else -> StreamingTransition(current)
        }
        StreamingEvent.NetworkAvailable -> if (current is StreamingState.WaitingForNetwork) reconnect(current.session, current.nextAttempt) else StreamingTransition(current)
        StreamingEvent.RetryElapsed -> if (current is StreamingState.BackingOff) StreamingTransition(StreamingState.Connecting(current.session, current.nextAttempt), listOf(StreamingAction.ConnectPeer(current.session))) else StreamingTransition(current)
        StreamingEvent.ViewerHidden, StreamingEvent.ClientPhoneLocked -> stop(current)
        StreamingEvent.SessionExpired -> expire(current)
    }

    private fun reconnect(session: RemoteStreamingSession, attempt: Int): StreamingTransition {
        if (attempt > maximumReconnectAttempts) return StreamingTransition(
            StreamingState.Failed("reconnect_exhausted"),
            listOf(StreamingAction.DisconnectPeer, StreamingAction.EndBackendSession(session.id))
        )
        val delay = (1_000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(15_000L)
        return StreamingTransition(StreamingState.BackingOff(session, attempt), listOf(StreamingAction.DisconnectPeer, StreamingAction.ScheduleReconnect(attempt, delay)))
    }

    private fun stop(current: StreamingState): StreamingTransition {
        val session = session(current)
        return StreamingTransition(
            StreamingState.Stopped,
            buildList {
                add(StreamingAction.DisconnectPeer)
                session?.let { add(StreamingAction.EndBackendSession(it.id)) }
            }
        )
    }

    private fun expire(current: StreamingState): StreamingTransition {
        val session = session(current)
        return StreamingTransition(
            StreamingState.Failed("session_expired"),
            buildList {
                add(StreamingAction.DisconnectPeer)
                session?.let { add(StreamingAction.EndBackendSession(it.id)) }
            }
        )
    }

    private fun session(state: StreamingState): RemoteStreamingSession? = when (state) {
        is StreamingState.Connecting -> state.session
        is StreamingState.Connected -> state.session
        is StreamingState.WaitingForNetwork -> state.session
        is StreamingState.BackingOff -> state.session
        else -> null
    }
}

interface WebRtcPeer : AutoCloseable {
    fun connect(session: RemoteStreamingSession)
    override fun close()
}

