package com.noobexon.xposedfakelocation.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All repository instances in the manager process share one ordered write queue. */
internal object RemotePreferenceWriteQueue {
    private val mutex = Mutex()

    suspend fun <T> write(action: suspend () -> T): T = mutex.withLock {
        // Acquire before service binding or dispatching to IO so a later STOP cannot
        // commit ahead of an earlier START that is still waiting to run.
        action()
    }
}
