package dev.xiaonuoboard.app.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll

/**
 * Tracks in-flight background jobs that read screen-owned bitmaps so the bitmaps are recycled only
 * after every reader has finished (prevents use-after-recycle on Back / tab switches).
 */
class JobTracker {
    private val jobs = LinkedHashSet<Job>()

    fun track(job: Job): Job {
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs -= job } }
        return job
    }

    val active: Boolean get() = synchronized(jobs) { jobs.any { it.isActive } }

    suspend fun joinAll() {
        val snapshot = synchronized(jobs) { jobs.toList() }
        snapshot.joinAll()
    }
}
