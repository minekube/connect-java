package com.minekube.connect.share.fabric

import com.minekube.connect.share.ConnectShareHandle
import com.minekube.connect.share.ConnectShareIngress
import com.minekube.connect.share.identity.CredentialSource
import com.minekube.connect.share.identity.EndpointIdentity
import io.netty.channel.local.LocalAddress
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class PersistentConnectIngressTest {
    @Test
    fun `title startup and world leases share one connector until shutdown`() =
        runBlocking {
            val delegate = FakeIngress()
            val persistent = PersistentConnectIngress(delegate)

            val starts = List(8) {
                async {
                    persistent.startControl(IDENTITY, TARGET)
                }
            }.awaitAll()

            assertTrue(starts.all { it.isRight() })
            assertEquals(1, delegate.starts.get())
            assertIs<PersistentConnectState.Available>(
                persistent.state.value,
            )

            val firstWorld = persistent.start(IDENTITY, TARGET)
            val secondWorld = persistent.start(IDENTITY, TARGET)
            firstWorld.close()
            secondWorld.close()

            assertEquals(0, delegate.closes.get())
            assertEquals(
                "stable.play.minekube.net",
                firstWorld.publicAddress,
            )

            persistent.shutdown()
            persistent.shutdown()

            assertEquals(1, delegate.closes.get())
            assertEquals(PersistentConnectState.Closed, persistent.state.value)
        }

    @Test
    fun `failed title startup can retry without leaking a connector`() =
        runBlocking {
            val delegate = FakeIngress(failuresBeforeSuccess = 1)
            val persistent = PersistentConnectIngress(delegate)

            val failed = persistent.startControl(IDENTITY, TARGET)

            assertTrue(failed.isLeft())
            assertIs<PersistentConnectState.Failed>(
                persistent.state.value,
            )
            val recovered = persistent.startControl(IDENTITY, TARGET)
            assertTrue(recovered.isRight())
            assertEquals(2, delegate.starts.get())
            persistent.shutdown()
            assertEquals(1, delegate.closes.get())
        }

    @Test
    fun `active connector rejects identity or target drift`() = runBlocking {
        val persistent = PersistentConnectIngress(FakeIngress())
        persistent.startControl(IDENTITY, TARGET).getOrNull()!!

        assertFailsWith<IllegalStateException> {
            persistent.start(
                IDENTITY.copy(endpoint = "other"),
                TARGET,
            )
        }
        assertFailsWith<IllegalStateException> {
            persistent.start(
                IDENTITY,
                LocalAddress("other-target"),
            )
        }

        persistent.shutdown()
    }

    @Test
    fun `restart releases the captured identity before the next control start`() =
        runBlocking {
            val delegate = FakeIngress()
            val persistent = PersistentConnectIngress(delegate)
            persistent.startControl(IDENTITY, TARGET).getOrNull()!!

            persistent.restart()

            assertEquals(1, delegate.closes.get())
            assertIs<PersistentConnectState.Idle>(persistent.state.value)
            persistent.startControl(
                IDENTITY.copy(endpoint = "replacement"),
                TARGET,
            ).getOrNull()!!
            assertEquals(2, delegate.starts.get())

            persistent.shutdown()
        }

    private class FakeIngress(
        private val failuresBeforeSuccess: Int = 0,
    ) : ConnectShareIngress {
        val starts = AtomicInteger()
        val closes = AtomicInteger()

        override suspend fun start(
            identity: EndpointIdentity,
            target: SocketAddress,
        ): ConnectShareHandle {
            val attempt = starts.incrementAndGet()
            if (attempt <= failuresBeforeSuccess) {
                error("simulated Connect startup failure")
            }
            return ConnectShareHandle(
                endpoint = identity.endpoint,
                publicAddress =
                    "${identity.endpoint}.play.minekube.net",
                close = {
                    closes.incrementAndGet()
                },
            )
        }
    }

    private companion object {
        val IDENTITY = EndpointIdentity(
            endpoint = "stable",
            token = "T-persistenttesttoken",
            endpointSource = CredentialSource.GENERATED,
            tokenSource = CredentialSource.GENERATED,
        )
        val TARGET: SocketAddress = LocalAddress("persistent-target")
    }
}
