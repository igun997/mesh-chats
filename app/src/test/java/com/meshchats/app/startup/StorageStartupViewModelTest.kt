package com.meshchats.app.startup

import com.meshchats.app.util.MainDispatcherRule
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/**
 * The startup ViewModel injects only the coordinator (never a DB/DAO/repository),
 * kicks off exactly one initialize in its scope, exposes the coordinator's state,
 * and forwards a retry action only when the current state is Failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageStartupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeCoordinator : DatabaseStartupCoordinator {
        val calls = AtomicInteger()
        private val _state = MutableStateFlow<DatabaseStartupState>(DatabaseStartupState.Idle)
        override val state: StateFlow<DatabaseStartupState> = _state

        override suspend fun initialize() {
            calls.incrementAndGet()
        }

        fun emit(next: DatabaseStartupState) {
            _state.value = next
        }
    }

    @Test
    fun `starts initialization once on construction`() {
        val coordinator = FakeCoordinator()

        StorageStartupViewModel(coordinator)

        assertEquals(1, coordinator.calls.get())
    }

    @Test
    fun `exposes the coordinator state`() {
        val coordinator = FakeCoordinator()
        val vm = StorageStartupViewModel(coordinator)

        assertSame(coordinator.state, vm.state)
        coordinator.emit(DatabaseStartupState.Ready)
        assertEquals(DatabaseStartupState.Ready, vm.state.value)
    }

    @Test
    fun `retry triggers another initialize when failed`() {
        val coordinator = FakeCoordinator()
        val vm = StorageStartupViewModel(coordinator)
        assertEquals(1, coordinator.calls.get())
        coordinator.emit(DatabaseStartupState.Failed(StorageStartupReason.KEY_UNAVAILABLE))

        vm.retry()

        assertEquals(2, coordinator.calls.get())
    }

    @Test
    fun `retry is a no-op while initializing`() {
        val coordinator = FakeCoordinator()
        val vm = StorageStartupViewModel(coordinator)
        assertEquals(1, coordinator.calls.get())
        coordinator.emit(DatabaseStartupState.Initializing)

        // Rapid taps while an attempt is in flight must not stack attempts.
        vm.retry()
        vm.retry()
        vm.retry()

        assertEquals(1, coordinator.calls.get())
    }

    @Test
    fun `retry is a no-op when already ready`() {
        val coordinator = FakeCoordinator()
        val vm = StorageStartupViewModel(coordinator)
        coordinator.emit(DatabaseStartupState.Ready)

        vm.retry()

        assertEquals(1, coordinator.calls.get())
    }
}
