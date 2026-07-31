package com.minekube.connect.share.fabric

import com.minekube.connect.share.ConnectShareHandle
import com.minekube.connect.share.ConnectShareIngress
import com.minekube.connect.share.identity.CredentialSource
import com.minekube.connect.share.identity.EndpointIdentity
import io.netty.channel.local.LocalAddress
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
class ConnectControlPlaneTest {
    @Test
    fun `startup and shutdown are scheduled off the caller dispatcher`() =
        runTest {
            val io = StandardTestDispatcher(testScheduler)
            val delegate = RecordingIngress()
            val persistent = PersistentConnectIngress(delegate)
            var identitiesLoaded = 0
            val control = ConnectControlPlane(
                scope = backgroundScope,
                ingress = persistent,
                identity = {
                    identitiesLoaded++
                    IDENTITY
                },
                target = TARGET,
                ioDispatcher = io,
            )

            control.start()

            assertEquals(0, identitiesLoaded)
            assertEquals(0, delegate.starts)
            runCurrent()
            assertEquals(1, identitiesLoaded)
            assertEquals(1, delegate.starts)
            assertIs<PersistentConnectState.Available>(
                control.state.value,
            )

            control.shutdown()

            assertEquals(1, delegate.closes)
            assertEquals(PersistentConnectState.Closed, control.state.value)
        }

    @Test
    fun `repeated starts share one in-flight title connector`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val delegate = RecordingIngress()
        val control = ConnectControlPlane(
            scope = backgroundScope,
            ingress = PersistentConnectIngress(delegate),
            identity = { IDENTITY },
            target = TARGET,
            ioDispatcher = io,
        )

        repeat(8) { control.start() }
        runCurrent()

        assertEquals(1, delegate.starts)
        control.shutdown()
    }

    @Test
    fun `shutdown cancels an in-flight connector startup`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        var cancellations = 0
        val delegate = ConnectShareIngress { _, _ ->
            try {
                awaitCancellation()
            } finally {
                cancellations++
            }
        }
        val control = ConnectControlPlane(
            scope = backgroundScope,
            ingress = PersistentConnectIngress(delegate),
            identity = { IDENTITY },
            target = TARGET,
            ioDispatcher = io,
        )
        control.start()
        runCurrent()

        control.shutdown()

        assertEquals(1, cancellations)
        assertEquals(PersistentConnectState.Closed, control.state.value)
    }

    private class RecordingIngress : ConnectShareIngress {
        var starts = 0
        var closes = 0

        override suspend fun start(
            identity: EndpointIdentity,
            target: SocketAddress,
        ): ConnectShareHandle {
            starts++
            return ConnectShareHandle(
                endpoint = identity.endpoint,
                publicAddress =
                    "${identity.endpoint}.play.minekube.net",
                close = { closes++ },
            )
        }
    }

    private companion object {
        val IDENTITY = EndpointIdentity(
            endpoint = "control",
            token = "T-controlplanetoken",
            endpointSource = CredentialSource.GENERATED,
            tokenSource = CredentialSource.GENERATED,
        )
        val TARGET: SocketAddress = LocalAddress("control-target")
    }
}
