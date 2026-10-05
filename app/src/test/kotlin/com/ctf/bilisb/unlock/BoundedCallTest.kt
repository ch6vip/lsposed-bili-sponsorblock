package com.ctf.bilisb.unlock

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class BoundedCallTest {
    @Test fun `timed out running task is interrupted and worker can recover`() {
        val worker = Executors.newSingleThreadExecutor()
        val interrupted = CountDownLatch(1)
        try {
            assertTrue(runCatching {
                BoundedCall.await(worker, 100) {
                    try { CountDownLatch(1).await() } catch (e: InterruptedException) {
                        interrupted.countDown()
                        throw e
                    }
                }
            }.exceptionOrNull() is TimeoutException)
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            assertEquals(42, BoundedCall.await(worker, 1000) { 42 })
        } finally { worker.shutdownNow() }
    }

    @Test fun `timed out queued task never runs after previous request finishes`() {
        val worker = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        val ran = AtomicBoolean(false)
        worker.submit { gate.await() }
        try {
            assertTrue(runCatching { BoundedCall.await(worker, 50) { ran.set(true) } }.isFailure)
            gate.countDown()
            BoundedCall.await(worker, 1000) { Unit }
            assertFalse(ran.get())
        } finally { gate.countDown(); worker.shutdownNow() }
    }
}
