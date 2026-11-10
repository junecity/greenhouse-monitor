package studio.junecity.greenhouse.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import studio.junecity.greenhouse.settings.CloudEnrollment

/** Restarts credential-bound work after pairing rotates a device's enrollment. */
internal class EnrollmentBoundWorker(private val scope: CoroutineScope) {
    private var enrollment: CloudEnrollment? = null
    private var job: Job? = null

    fun start(current: CloudEnrollment?, block: suspend () -> Unit) {
        if (job?.isActive == true && enrollment == current) return
        val previous = job
        enrollment = current
        // Stop the old camera/session before a newly authorized worker can acquire it.
        previous?.cancel()
        job = scope.launch {
            previous?.join()
            block()
        }
    }
}
