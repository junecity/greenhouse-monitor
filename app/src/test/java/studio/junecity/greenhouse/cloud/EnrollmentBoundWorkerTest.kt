package studio.junecity.greenhouse.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import studio.junecity.greenhouse.settings.CloudEnrollment

class EnrollmentBoundWorkerTest {
    @Test fun rePairWaitsForOldSessionCleanupAndUsesNewCredential() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val releaseCleanup = CompletableDeferred<Unit>()
        try {
            val worker = EnrollmentBoundWorker(scope)
            val first = CloudEnrollment("server-device", "greenhouse-one", CloudDeviceRole.GreenhouseServer, "old-secret")
            val started = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val newStarted = CompletableDeferred<Unit>()
            var starts = 0
            worker.start(first) {
                starts++
                started.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleanupStarted.complete(Unit); releaseCleanup.await() }
                }
            }
            withTimeout(2_000) { started.await() }
            worker.start(first) { starts++ }
            yield()
            assertEquals(1, starts)
            worker.start(first.copy(deviceSecret = "rotated-secret")) { starts++; newStarted.complete(Unit) }
            withTimeout(2_000) { cleanupStarted.await() }
            assertFalse(newStarted.isCompleted)
            releaseCleanup.complete(Unit)
            withTimeout(2_000) { newStarted.await() }
            assertEquals(2, starts)
        } finally { releaseCleanup.complete(Unit); scope.cancel() }
    }
}
