package com.noobexon.xposedfakelocation.data.repository

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePreferenceWriteQueueTest {
    @Test(timeout = 5_000)
    fun startWaitingForDispatchCannotCommitAfterLaterStop() = runBlocking {
        val releaseStart = CompletableDeferred<Unit>()
        val writes = mutableListOf<Boolean>()
        // Separate repository instances use this same process-wide queue.
        val firstRepositoryQueue = RemotePreferenceWriteQueue
        val secondRepositoryQueue = RemotePreferenceWriteQueue
        val start = launch(start = CoroutineStart.UNDISPATCHED) {
            firstRepositoryQueue.write {
                releaseStart.await()
                writes.add(true)
            }
        }
        val stop = launch(start = CoroutineStart.UNDISPATCHED) {
            secondRepositoryQueue.write { writes.add(false) }
        }
        assertTrue(writes.isEmpty())
        releaseStart.complete(Unit)
        joinAll(start, stop)
        assertEquals(listOf(true, false), writes)
        assertFalse(writes.last())
    }

    @Test(timeout = 5_000)
    fun clearWaitsForCancelledSynchronousTickThenClearsBothKeys() = runBlocking {
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        var location: String? = "initial"
        var playing = true
        val ticker = launch(start = CoroutineStart.UNDISPATCHED) {
            RemotePreferenceWriteQueue.write {
                withContext(Dispatchers.IO) {
                    commitEntered.countDown()
                    check(releaseCommit.await(2, TimeUnit.SECONDS))
                    location = "late tick"
                }
            }
        }
        assertTrue(commitEntered.await(2, TimeUnit.SECONDS))
        ticker.cancel()
        val clear = launch(start = CoroutineStart.UNDISPATCHED) {
            ticker.join()
            RemotePreferenceWriteQueue.write {
                location = null
                playing = false
            }
        }
        assertEquals("initial", location)
        releaseCommit.countDown()
        joinAll(ticker, clear)
        assertNull(location)
        assertFalse(playing)
    }

    @Test(timeout = 5_000)
    fun failedWriteDoesNotBlockTheFollowingStop() = runBlocking {
        try {
            RemotePreferenceWriteQueue.write { error("commit failed") }
        } catch (_: IllegalStateException) {
            // The mutex must be released even if a remote editor throws.
        }
        var playing = true
        RemotePreferenceWriteQueue.write { playing = false }
        assertFalse(playing)
    }
}
