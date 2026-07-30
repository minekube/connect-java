package com.minekube.connect.share.fabric

import com.minekube.connect.identity.EndpointTokenStore
import com.minekube.connect.share.admission.AdmissionController
import com.minekube.connect.share.identity.CredentialSource
import com.minekube.connect.share.identity.EndpointIdentity
import com.minekube.connect.share.identity.EndpointIdentityStore
import com.minekube.connect.watch.SessionAdmissionGate
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class FabricConnectIngressTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `start reuses token file and returns stable public address`() = runTest {
        val tokenFile = tempDir.resolve(EndpointIdentityStore.TOKEN_FILE_NAME)
        EndpointTokenStore().save(tokenFile, IDENTITY.token)
        val before = Files.readAllBytes(tokenFile)
        val closes = AtomicInteger()
        val factory = RecordingRuntimeFactory(closes)
        val ingress = ingress(factory)

        val handle = ingress.start(IDENTITY, TARGET)

        assertEquals("amber-fox", handle.endpoint)
        assertEquals("amber-fox.play.minekube.net", handle.publicAddress)
        assertContentEquals(before, Files.readAllBytes(tokenFile))
        assertEquals(TARGET, factory.target)
        assertEquals(IDENTITY, factory.identity)
        handle.close()
        handle.close()
        assertEquals(1, closes.get())
    }

    @Test
    fun `start refuses to create a missing token`() = runTest {
        val factory = RecordingRuntimeFactory(AtomicInteger())
        val ingress = ingress(factory)

        assertFailsWith<IllegalStateException> {
            ingress.start(IDENTITY, TARGET)
        }

        assertEquals(0, factory.starts)
        assertEquals(false, Files.exists(tempDir.resolve(EndpointIdentityStore.TOKEN_FILE_NAME)))
    }

    private fun kotlinx.coroutines.test.TestScope.ingress(
        factory: FabricConnectRuntimeFactory,
    ): FabricConnectIngress {
        val admission = AdmissionController(
            scope = backgroundScope,
            timeout = 30.seconds,
            maxPending = 16,
            connectedCount = { 0 },
            maxGuests = { 8 },
        )
        return FabricConnectIngress.testing(
            dataDirectory = tempDir,
            admission = admission,
            scope = backgroundScope,
            runtimeFactory = factory,
        )
    }

    private class RecordingRuntimeFactory(
        private val closes: AtomicInteger,
    ) : FabricConnectRuntimeFactory {
        var starts: Int = 0
        var identity: EndpointIdentity? = null
        var target: java.net.SocketAddress? = null

        override fun start(
            identity: EndpointIdentity,
            target: java.net.SocketAddress,
            admissionGate: SessionAdmissionGate,
        ): FabricConnectRuntime {
            starts++
            this.identity = identity
            this.target = target
            return FabricConnectRuntime {
                closes.incrementAndGet()
            }
        }
    }

    private companion object {
        val TARGET = InetSocketAddress.createUnresolved("127.0.0.1", 25565)
        val IDENTITY = EndpointIdentity(
            endpoint = "amber-fox",
            token = "T-AAAAAAAAAAAAAAAAAAAA",
            endpointSource = CredentialSource.GENERATED,
            tokenSource = CredentialSource.GENERATED,
        )
    }
}
