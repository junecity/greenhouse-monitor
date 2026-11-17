package studio.junecity.greenhouse.camera

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Converts only the next requested camera frame, on the capturer thread. */
internal class CameraSamples {
    private val pending = AtomicReference<CompletableDeferred<Bitmap?>?>()

    suspend fun next(): Bitmap? {
        val wait = CompletableDeferred<Bitmap?>()
        check(pending.compareAndSet(null, wait))
        return try {
            withTimeoutOrNull(2500) { wait.await() }
        } finally {
            pending.compareAndSet(wait, null)
        }
    }

    fun offer(image: () -> Bitmap?) {
        val wait = pending.getAndSet(null) ?: return
        try { wait.complete(image()) }
        catch (e: Exception) { wait.completeExceptionally(e) }
    }

    fun requested() = pending.get() != null
}
