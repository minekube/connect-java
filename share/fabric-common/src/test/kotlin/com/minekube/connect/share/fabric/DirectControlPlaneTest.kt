package com.minekube.connect.share.fabric

import com.minekube.connect.share.DirectShareHandle
import com.minekube.connect.share.DirectShareIngress
import com.minekube.connect.share.ShareGameMode
import com.minekube.connect.share.ShareOptions
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DirectControlPlaneTest {
    @Test
    fun `startup and shutdown are scheduled off the caller dispatcher`() =
        runTest {
            val io = StandardTestDispatcher(testScheduler)
            val delegate = RecordingIngress()
            val persistent = PersistentDirectIngress(delegate)
            var addressesLoaded = 0
            val control = DirectControlPlane(
                scope = backgroundScope,
                ingress = persistent,
                options = OPTIONS,
                target = TARGET,
                connectAddress = {
                    addressesLoaded++
                    CONNECT_ADDRESS
                },
                ioDispatcher = io,
            )

            control.start()

            assertEquals(0, addressesLoaded)
            assertEquals(0, delegate.starts)
            runCurrent()
            assertEquals(1, addressesLoaded)
            assertEquals(1, delegate.starts)
            assertIs<PersistentDirectState.Available>(
                control.state.value,
            )

            control.shutdown()

            assertEquals(1, delegate.closes)
            assertEquals(PersistentDirectState.Closed, control.state.value)
        }

    @Test
    fun `repeated starts share one in-flight title direct host`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val delegate = RecordingIngress()
        val control = DirectControlPlane(
            scope = backgroundScope,
            ingress = PersistentDirectIngress(delegate),
            options = OPTIONS,
            target = TARGET,
            connectAddress = { CONNECT_ADDRESS },
            ioDispatcher = io,
        )

        repeat(8) { control.start() }
        runCurrent()

        assertEquals(1, delegate.starts)
        control.shutdown()
    }

    @Test
    fun `shutdown cancels an in-flight direct host startup`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        var cancellations = 0
        val delegate = DirectShareIngress { _, _, _ ->
            try {
                awaitCancellation()
            } finally {
                cancellations++
            }
        }
        val control = DirectControlPlane(
            scope = backgroundScope,
            ingress = PersistentDirectIngress(delegate),
            options = OPTIONS,
            target = TARGET,
            connectAddress = { CONNECT_ADDRESS },
            ioDispatcher = io,
        )
        control.start()
        runCurrent()

        control.shutdown()

        assertEquals(1, cancellations)
        assertEquals(PersistentDirectState.Closed, control.state.value)
    }

    private class RecordingIngress : DirectShareIngress {
        var starts = 0
        var closes = 0

        override suspend fun start(
            options: ShareOptions,
            target: SocketAddress,
            connectAddress: String?,
        ): DirectShareHandle {
            starts++
            return DirectShareHandle(
                invitation = "minekube://share/persistent-control",
                lanAvailable = true,
                internetAvailable = false,
                close = { closes++ },
            )
        }
    }

    private companion object {
        const val CONNECT_ADDRESS = "control.play.minekube.net"
        val TARGET: SocketAddress =
            InetSocketAddress(InetAddress.getLoopbackAddress(), 25_565)
        val OPTIONS = ShareOptions(
            gameMode = ShareGameMode.SURVIVAL,
            allowCheats = false,
            allowInternetDirect = false,
        )
    }
}
