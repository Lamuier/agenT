package com.lamuier.agenT.data

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class InFlightRequestsTest {
    @Test fun callersShareAnActiveRequestAndLaterCallsCanRefreshAgain() {
        val requests = InFlightRequests<String, Int>()
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val count = AtomicInteger(); val values = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val owner = Thread { values += requests.run("account:r1") {
            count.incrementAndGet(); entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)); 42
        } }.apply { start() }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val waiter = Thread { values += requests.run("account:r1") { count.incrementAndGet(); -1 } }.apply { start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (waiter.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
        assertEquals(Thread.State.WAITING, waiter.state)
        assertEquals(9, requests.run("account:r2") { 9 })
        finish.countDown(); owner.join(3000); waiter.join(3000)
        assertEquals(listOf(42, 42), values.sorted())
        assertEquals(1, count.get())
        assertEquals(43, requests.run("account:r1") { count.incrementAndGet(); 43 })
        assertEquals(2, count.get())
    }

    @Test fun failuresDoNotPoisonRetries() {
        val requests = InFlightRequests<String, Int>()
        val failure = IOException("fixture")
        assertSame(failure, runCatching { requests.run("key") { throw failure } }.exceptionOrNull())
        assertEquals(7, requests.run("key") { 7 })
    }
}
