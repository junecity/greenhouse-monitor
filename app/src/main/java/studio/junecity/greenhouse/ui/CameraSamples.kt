package studio.junecity.greenhouse.ui

import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Scoped to the mounted camera tab. Only convert a frame while a recording requests it. */
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
        wait.complete(runCatching(image).getOrNull())
    }

    fun requested() = pending.get() != null
}

internal val LocalCameraSamples = staticCompositionLocalOf<CameraSamples?> { null }
