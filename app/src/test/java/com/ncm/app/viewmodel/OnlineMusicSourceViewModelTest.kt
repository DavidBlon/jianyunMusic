package com.ncm.app.viewmodel

import com.ncm.app.plugin.auth.LinglanAuthState
import com.ncm.app.plugin.runtime.InMemoryPluginRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMusicSourceViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun selectSourceUpdatesSelectedPluginIdAndKeepsOthersUnselected() = runTest(dispatcher) {
        val vm = OnlineMusicSourceViewModel(
            manifestProvider = { sampleManifest() },
            runtime = InMemoryPluginRuntime(emptyMap())
        )
        vm.refreshManifest()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, vm.uiState.value.manifestItems.size)
        vm.selectSource("linglan.kw")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("linglan.kw", vm.uiState.value.selectedPluginId)
        vm.selectSource("linglan.wy")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("linglan.wy", vm.uiState.value.selectedPluginId)
    }

    @Test
    fun connectFlowRejectsShortSecretAndAcceptsLongOne() = runTest(dispatcher) {
        val vm = OnlineMusicSourceViewModel(
            manifestProvider = { sampleManifest() },
            runtime = InMemoryPluginRuntime(emptyMap())
        )
        vm.connect("short")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LinglanAuthState.ERROR, vm.uiState.value.authState)

        vm.connect("valid-secret-1234")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LinglanAuthState.ACTIVE, vm.uiState.value.authState)
        assertEquals(3, vm.uiState.value.manifestItems.size) // 验证成功后自动拉取清单
    }

    @Test
    fun disconnectResetsStateAndDestroysRuntime() = runTest(dispatcher) {
        val runtime = InMemoryPluginRuntime(emptyMap())
        val vm = OnlineMusicSourceViewModel(
            manifestProvider = { sampleManifest() },
            runtime = runtime
        )
        vm.connect("valid-secret-1234")
        dispatcher.scheduler.advanceUntilIdle()
        vm.disconnect()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LinglanAuthState.DISCONNECTED, vm.uiState.value.authState)
        assertEquals(null, vm.uiState.value.selectedPluginId)
        assertEquals(false, runtime.isHealthy())
    }
}
