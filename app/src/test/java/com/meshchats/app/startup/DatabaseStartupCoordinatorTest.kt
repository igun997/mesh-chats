package com.meshchats.app.startup

import com.meshchats.app.data.local.EncryptedDatabaseError
import com.meshchats.app.data.local.EncryptedDatabaseException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit contract for [DefaultDatabaseStartupCoordinator]: the encrypted-storage
 * open must run on the injected IO dispatcher (never the caller/main thread),
 * concurrent starts must share a single attempt, retry after failure must be
 * allowed, `Ready` must be idempotent, wrapped [EncryptedDatabaseException]s must
 * classify to bounded reasons through a cycle-safe cause walk, and cancellation
 * must reset to `Idle` (never surface as UNEXPECTED, never stick in Initializing).
 *
 * A real two-thread IO dispatcher is used so the force-open thread is observable
 * and distinct from the caller, and so a blocking attempt can be coordinated from
 * the test without deadlocking the caller. This avoids brittle virtual-time
 * timing while keeping the concurrency real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseStartupCoordinatorTest {

    private val ioExecutor = Executors.newFixedThreadPool(2) { r -> Thread(r, IO_THREAD_PREFIX) }
    private val ioDispatcher = ioExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @After
    fun tearDown() {
        ioExecutor.shutdownNow()
    }

    private fun coordinator(forceOpen: DatabaseForceOpen) =
        DefaultDatabaseStartupCoordinator(
            forceOpen = forceOpen,
            ioDispatcher = ioDispatcher,
        )

    // Coroutine debugging appends " @coroutine#N" to the thread name; compare the
    // underlying pool thread name only.
    private fun baseName(name: String) = name.substringBefore(" @")

    @Test
    fun `force-open runs on injected io dispatcher not the caller thread`() = runBlocking {
        val callerThread = Thread.currentThread().name
        val openThread = AtomicReference<String>()
        val coordinator = coordinator { openThread.set(Thread.currentThread().name) }

        coordinator.initialize()

        assertEquals(IO_THREAD_PREFIX, baseName(openThread.get()))
        assertNotEquals(baseName(callerThread), baseName(openThread.get()))
        assertEquals(DatabaseStartupState.Ready, coordinator.state.value)
    }

    @Test
    fun `forces the database open exactly once and reaches ready`() = runBlocking {
        val opens = AtomicInteger()
        val coordinator = coordinator { opens.incrementAndGet() }

        coordinator.initialize()

        assertEquals(1, opens.get())
        assertEquals(DatabaseStartupState.Ready, coordinator.state.value)
    }

    @Test
    fun `ready is idempotent and does not reopen`() = runBlocking {
        val opens = AtomicInteger()
        val coordinator = coordinator { opens.incrementAndGet() }

        coordinator.initialize()
        coordinator.initialize()

        assertEquals(1, opens.get())
        assertEquals(DatabaseStartupState.Ready, coordinator.state.value)
    }

    @Test(timeout = 5_000)
    fun `concurrent starts resolve through a single attempt`() {
        val opens = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = coordinator {
            opens.incrementAndGet()
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS)) { "attempt was not released" }
        }

        // Launch both attempts on the IO scope so neither depends on the test
        // thread's event loop; the test thread stays free to coordinate the latch.
        val first = scope.launch { coordinator.initialize() }
        assertTrue("first attempt did not start", entered.await(2, TimeUnit.SECONDS))
        val second = scope.launch { coordinator.initialize() }
        release.countDown()
        runBlocking {
            first.join()
            second.join()
        }

        assertEquals(1, opens.get())
        assertEquals(DatabaseStartupState.Ready, coordinator.state.value)
    }

    @Test(timeout = 5_000)
    fun `concurrent starts that fail attempt open only once`() {
        val opens = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = coordinator {
            opens.incrementAndGet()
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS)) { "attempt was not released" }
            throw EncryptedDatabaseException(EncryptedDatabaseError.KEY_UNAVAILABLE)
        }

        // A backlog of concurrent callers must collapse onto the one in-flight
        // attempt; a failure must not fan out into repeated opens.
        val first = scope.launch { coordinator.initialize() }
        assertTrue("first attempt did not start", entered.await(2, TimeUnit.SECONDS))
        val second = scope.launch { coordinator.initialize() }
        val third = scope.launch { coordinator.initialize() }
        // Prove both concurrent callers actually entered while the first attempt
        // was still blocked. They must observe Initializing and return before we
        // release the failing attempt. Releasing immediately after launch is a
        // race: a queued caller may start only after Failed is published, where it
        // correctly represents an explicit retry and would open again.
        runBlocking {
            withTimeout(2_000) {
                second.join()
                third.join()
            }
        }
        release.countDown()
        runBlocking { first.join() }

        assertEquals(1, opens.get())
        assertEquals(
            DatabaseStartupState.Failed(StorageStartupReason.KEY_UNAVAILABLE),
            coordinator.state.value,
        )
    }

    @Test
    fun `failed attempt can be retried to ready`() = runBlocking {
        val calls = AtomicInteger()
        val coordinator = coordinator {
            if (calls.getAndIncrement() == 0) {
                throw EncryptedDatabaseException(EncryptedDatabaseError.KEY_UNAVAILABLE)
            }
        }

        coordinator.initialize()
        assertEquals(
            DatabaseStartupState.Failed(StorageStartupReason.KEY_UNAVAILABLE),
            coordinator.state.value,
        )

        coordinator.initialize()
        assertEquals(DatabaseStartupState.Ready, coordinator.state.value)
        assertEquals(2, calls.get())
    }

    @Test
    fun `wrapped encrypted database exception classifies by bounded reason`() = runBlocking {
        val coordinator = coordinator {
            // Simulate Hilt/Provision wrapping of the real cause.
            throw RuntimeException(
                "provision failed",
                IllegalStateException(
                    EncryptedDatabaseException(EncryptedDatabaseError.MIGRATION_FAILED),
                ),
            )
        }

        coordinator.initialize()

        assertEquals(
            DatabaseStartupState.Failed(StorageStartupReason.MIGRATION_FAILED),
            coordinator.state.value,
        )
    }

    @Test
    fun `unknown failure classifies as unexpected without leaking detail`() = runBlocking {
        val coordinator = coordinator { throw IllegalStateException("boom secret 0xdeadbeef") }

        coordinator.initialize()

        val state = coordinator.state.value
        assertEquals(DatabaseStartupState.Failed(StorageStartupReason.UNEXPECTED), state)
    }

    @Test(timeout = 5_000)
    fun `cyclic cause chain terminates and classifies unexpected`() = runBlocking {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b) // a -> b -> a cycle, no EncryptedDatabaseException present
        val coordinator = coordinator { throw b }

        coordinator.initialize()

        assertEquals(
            DatabaseStartupState.Failed(StorageStartupReason.UNEXPECTED),
            coordinator.state.value,
        )
    }

    @Test
    fun `cancellation resets to idle and does not publish unexpected`() = runBlocking {
        val coordinator = coordinator { throw CancellationException("cancelled") }

        try {
            coordinator.initialize()
        } catch (_: CancellationException) {
            // expected to propagate to the awaiting caller
        }

        // Reset to Idle before rethrow: never stuck in Initializing, never Failed.
        assertEquals(DatabaseStartupState.Idle, coordinator.state.value)
    }

    @Test
    fun `attempt cancelled once can be re-driven to ready`() = runBlocking {
        val calls = AtomicInteger()
        val coordinator = coordinator {
            if (calls.getAndIncrement() == 0) throw CancellationException("cancelled")
        }

        try {
            coordinator.initialize()
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals(DatabaseStartupState.Idle, coordinator.state.value)

        // A fresh caller re-drives from the clean Idle slate and succeeds.
        coordinator.initialize()
        assertEquals(DatabaseStartupState.Ready, coordinator.state.value)
        assertEquals(2, calls.get())
    }

    private companion object {
        const val IO_THREAD_PREFIX = "test-io-dispatcher"
    }
}
