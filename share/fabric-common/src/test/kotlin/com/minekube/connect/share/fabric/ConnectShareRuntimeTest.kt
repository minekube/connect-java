package com.minekube.connect.share.fabric

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectShareRuntimeTest {
    @Test
    fun `world lifecycle work is scheduled off the caller dispatcher`() =
        runTest {
            val lifecycleDispatcher =
                StandardTestDispatcher(testScheduler)
            var resumeCalls = 0
            val runtime = ConnectShareRuntime(
                scope = this,
                stopShare = {},
                resumeShare = { resumeCalls++ },
                lifecycleDispatcher = lifecycleDispatcher,
            )

            runtime.integratedWorldChanged(worldAvailable = true)

            assertEquals(0, resumeCalls)
            runCurrent()
            assertEquals(1, resumeCalls)
        }

    @Test
    fun `leaving a world stops the active share exactly once`() = runTest {
        var stopCalls = 0
        val runtime = ConnectShareRuntime(
            scope = this,
            stopShare = {
                stopCalls++
            },
            lifecycleDispatcher =
                StandardTestDispatcher(testScheduler),
        )

        runtime.integratedWorldChanged(worldAvailable = true)
        runtime.integratedWorldChanged(worldAvailable = false)
        runtime.integratedWorldChanged(worldAvailable = false)
        advanceUntilIdle()

        assertEquals(1, stopCalls)
    }

    @Test
    fun `replacing an integrated world stops the previous share`() = runTest {
        var stopCalls = 0
        val runtime = ConnectShareRuntime(
            scope = this,
            stopShare = {
                stopCalls++
            },
            lifecycleDispatcher =
                StandardTestDispatcher(testScheduler),
        )

        runtime.integratedWorldChanged(worldAvailable = true, identity = "one")
        runtime.integratedWorldChanged(worldAvailable = true, identity = "two")
        advanceUntilIdle()

        assertEquals(1, stopCalls)
    }

    @Test
    fun `enabled sharing resumes when the host enters or switches worlds`() = runTest {
        val lifecycle = mutableListOf<String>()
        val runtime = ConnectShareRuntime(
            scope = this,
            stopShare = {
                lifecycle += "stop"
            },
            resumeShare = {
                lifecycle += "resume"
            },
            lifecycleDispatcher =
                StandardTestDispatcher(testScheduler),
        )

        runtime.integratedWorldChanged(worldAvailable = true, identity = "one")
        advanceUntilIdle()
        runtime.integratedWorldChanged(worldAvailable = true, identity = "two")
        advanceUntilIdle()
        runtime.integratedWorldChanged(worldAvailable = false)
        advanceUntilIdle()

        assertEquals(
            listOf("resume", "stop", "resume", "stop"),
            lifecycle,
        )
    }
}
