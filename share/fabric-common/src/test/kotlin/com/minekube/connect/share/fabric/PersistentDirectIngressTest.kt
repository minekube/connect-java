package com.minekube.connect.share.fabric

import com.minekube.connect.share.DirectShareHandle
import com.minekube.connect.share.DirectShareIngress
import com.minekube.connect.share.ShareGameMode
import com.minekube.connect.share.ShareOptions
import java.net.InetAddress
import java.net.InetSocketAddress
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

class PersistentDirectIngressTest {
    @Test
    fun `world starts replace the title host and publish current invitations`() =
        runBlocking {
            val delegate = FakeIngress()
            val persistent = PersistentDirectIngress(delegate)

            val starts = List(8) {
                async {
                    persistent.startControl(
                        CONTROL_OPTIONS,
                        TARGET,
                        CONNECT_ADDRESS,
                    )
                }
            }.awaitAll()

            assertTrue(starts.all { it.isRight() })
            assertEquals(1, delegate.starts.get())
            assertIs<PersistentDirectState.Available>(
                persistent.state.value,
            )

            val firstWorld = persistent.start(
                CONTROL_OPTIONS,
                TARGET,
                CONNECT_ADDRESS,
            )
            val secondWorld = persistent.start(
                CONTROL_OPTIONS.copy(allowInternetDirect = true),
                TARGET,
                CONNECT_ADDRESS,
            )
            firstWorld.close()
            secondWorld.close()

            assertEquals(2, delegate.closes.get())
            assertEquals("$INVITATION-2", firstWorld.invitation)
            assertTrue(firstWorld.lanAvailable)
            assertEquals("$INVITATION-3", secondWorld.invitation)
            assertEquals(3, delegate.starts.get())
            assertEquals(
                listOf(false, false, true),
                delegate.startedOptions.map(ShareOptions::allowInternetDirect),
            )

            persistent.shutdown()
            persistent.shutdown()

            assertEquals(3, delegate.closes.get())
            assertEquals(PersistentDirectState.Closed, persistent.state.value)
        }

    @Test
    fun `failed title startup can retry without leaking a direct host`() =
        runBlocking {
            val delegate = FakeIngress(failuresBeforeSuccess = 1)
            val persistent = PersistentDirectIngress(delegate)

            val failed = persistent.startControl(
                CONTROL_OPTIONS,
                TARGET,
                CONNECT_ADDRESS,
            )

            assertTrue(failed.isLeft())
            assertIs<PersistentDirectState.Failed>(persistent.state.value)
            val recovered = persistent.startControl(
                CONTROL_OPTIONS,
                TARGET,
                CONNECT_ADDRESS,
            )
            assertTrue(recovered.isRight())
            assertEquals(2, delegate.starts.get())

            persistent.shutdown()
            assertEquals(1, delegate.closes.get())
        }

    @Test
    fun `active direct host rejects target or Connect address drift`() =
        runBlocking {
            val persistent = PersistentDirectIngress(FakeIngress())
            persistent.startControl(
                CONTROL_OPTIONS,
                TARGET,
                CONNECT_ADDRESS,
            ).getOrNull()!!

            assertFailsWith<IllegalStateException> {
                persistent.startControl(
                    CONTROL_OPTIONS,
                    InetSocketAddress(InetAddress.getLoopbackAddress(), 25_566),
                    CONNECT_ADDRESS,
                )
            }
            assertFailsWith<IllegalStateException> {
                persistent.startControl(
                    CONTROL_OPTIONS,
                    TARGET,
                    "other.play.minekube.net",
                )
            }

            persistent.shutdown()
        }

    private class FakeIngress(
        private val failuresBeforeSuccess: Int = 0,
    ) : DirectShareIngress {
        val starts = AtomicInteger()
        val closes = AtomicInteger()
        val startedOptions = mutableListOf<ShareOptions>()

        override suspend fun start(
            options: ShareOptions,
            target: SocketAddress,
            connectAddress: String?,
        ): DirectShareHandle {
            val attempt = starts.incrementAndGet()
            startedOptions += options
            if (attempt <= failuresBeforeSuccess) {
                error("simulated direct startup failure")
            }
            return DirectShareHandle(
                invitation = "$INVITATION-$attempt",
                lanAvailable = true,
                internetAvailable = false,
                close = {
                    closes.incrementAndGet()
                },
            )
        }
    }

    private companion object {
        const val CONNECT_ADDRESS = "stable.play.minekube.net"
        const val INVITATION = "minekube://share/signed-persistent"
        val TARGET: SocketAddress =
            InetSocketAddress(InetAddress.getLoopbackAddress(), 25_565)
        val CONTROL_OPTIONS = ShareOptions(
            gameMode = ShareGameMode.SURVIVAL,
            allowCheats = false,
            allowInternetDirect = false,
        )
    }
}
