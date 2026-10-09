package com.noobexon.xposedfakelocation.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** All repository instances in the manager process share one ordered write queue. */
internal object RemotePreferenceWriteQueue {
    private val mutex = Mutex()

    suspend fun <T> write(timeoutMs: Long = 3_000L, action: suspend () -> T): T? =
        withTimeoutOrNull(timeoutMs) {
            // One request budget includes both queuing and service binding. Giving
            // each lock holder a fresh binding timeout would accumulate N timeouts.
            mutex.withLock {
                // Acquire before service binding or dispatching to IO so a later STOP
                // cannot commit ahead of an earlier START still waiting to run.
                action()
            }
        }
}
