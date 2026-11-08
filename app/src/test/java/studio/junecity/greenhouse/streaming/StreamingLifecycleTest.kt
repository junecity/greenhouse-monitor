package studio.junecity.greenhouse.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.junecity.greenhouse.cloud.RemoteStreamingSession

class StreamingLifecycleTest {
    private val session = RemoteStreamingSession("session", "greenhouse", "server", "viewer", "arn", "us-east-1", emptyList(), Long.MAX_VALUE)

    @Test fun streamStartsOnlyAfterAuthorization() {
        val lifecycle = StreamingLifecycle()
        val requested = lifecycle.accept(StreamingEvent.ViewerBecameVisible)
        assertEquals(StreamingState.Authorizing, requested.state)
        assertEquals(listOf(StreamingAction.RequestAuthorization), requested.actions)
        val granted = lifecycle.accept(StreamingEvent.AuthorizationGranted(session))
        assertEquals(StreamingState.Connecting(session, 0), granted.state)
        assertEquals(listOf(StreamingAction.ConnectPeer(session)), granted.actions)
    }

    @Test fun deniedAuthorizationNeverConnects() {
        val lifecycle = StreamingLifecycle()
        lifecycle.accept(StreamingEvent.ViewerBecameVisible)
        val denied = lifecycle.accept(StreamingEvent.AuthorizationDenied("premium_required"))
        assertEquals(StreamingState.Failed("premium_required"), denied.state)
        assertTrue(denied.actions.none { it is StreamingAction.ConnectPeer })
    }

    @Test fun hidingViewerDisconnectsAndEndsSession() {
        val lifecycle = connectedLifecycle()
        val stopped = lifecycle.accept(StreamingEvent.ViewerHidden)
        assertEquals(StreamingState.Stopped, stopped.state)
        assertEquals(listOf(StreamingAction.DisconnectPeer, StreamingAction.EndBackendSession("session")), stopped.actions)
    }

    @Test fun lockingPhoneDisconnectsAndEndsSession() {
        val lifecycle = connectedLifecycle()
        val stopped = lifecycle.accept(StreamingEvent.ClientPhoneLocked)
        assertEquals(StreamingState.Stopped, stopped.state)
        assertTrue(StreamingAction.DisconnectPeer in stopped.actions)
        assertTrue(StreamingAction.EndBackendSession("session") in stopped.actions)
    }

    @Test fun networkHandoffWaitsThenReconnectsWithBackoff() {
        val lifecycle = connectedLifecycle()
        val lost = lifecycle.accept(StreamingEvent.NetworkLost)
        assertEquals(StreamingState.WaitingForNetwork(session, 1), lost.state)
        assertEquals(listOf(StreamingAction.DisconnectPeer), lost.actions)
        val available = lifecycle.accept(StreamingEvent.NetworkAvailable)
        assertEquals(StreamingState.BackingOff(session, 1), available.state)
        assertTrue(available.actions.any { it is StreamingAction.ScheduleReconnect })
        val retry = lifecycle.accept(StreamingEvent.RetryElapsed)
        assertEquals(StreamingState.Connecting(session, 1), retry.state)
        assertEquals(listOf(StreamingAction.ConnectPeer(session)), retry.actions)
    }

    @Test fun repeatedFailureIsBoundedAndEndsBackendSession() {
        val lifecycle = StreamingLifecycle(maximumReconnectAttempts = 2)
        lifecycle.accept(StreamingEvent.ViewerBecameVisible)
        lifecycle.accept(StreamingEvent.AuthorizationGranted(session))
        lifecycle.accept(StreamingEvent.PeerDisconnected)
        lifecycle.accept(StreamingEvent.RetryElapsed)
        lifecycle.accept(StreamingEvent.PeerDisconnected)
        lifecycle.accept(StreamingEvent.RetryElapsed)
        val exhausted = lifecycle.accept(StreamingEvent.PeerDisconnected)
        assertEquals(StreamingState.Failed("reconnect_exhausted"), exhausted.state)
        assertTrue(StreamingAction.EndBackendSession("session") in exhausted.actions)
    }

    private fun connectedLifecycle() = StreamingLifecycle().also {
        it.accept(StreamingEvent.ViewerBecameVisible)
        it.accept(StreamingEvent.AuthorizationGranted(session))
        it.accept(StreamingEvent.PeerConnected)
    }
}

