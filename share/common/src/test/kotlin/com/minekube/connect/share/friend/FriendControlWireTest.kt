package com.minekube.connect.share.friend

import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class FriendControlWireTest {
    @Test
    fun `request is a raw control frame instead of a Minecraft status ping`() {
        val request = FriendControlRequest(
            requestId = REQUEST_ID,
            displayName = "bob",
            invitation = "minekube://share/signed-bob-card",
        )

        val encoded = FriendControlWire.encodeRequest(
            request = request,
        )
        val decoded = assertIs<FriendControlDecode.Decoded<FriendControlRequest>>(
            FriendControlWire.decodeRequest(encoded),
        )

        assertEquals(request, decoded.value)
        assertEquals(encoded.size, decoded.consumedBytes)
        assertFalse(FriendControlWire.isStatusHandshake(encoded))
    }

    @Test
    fun `new decoder accepts legacy request frames with request id fallback`() {
        val request = FriendControlRequest(
            requestId = REQUEST_ID,
            displayName = "bob",
            invitation = "minekube://share/signed-bob-card",
        )

        val decoded = assertIs<FriendControlDecode.Decoded<FriendControlRequest>>(
            FriendControlWire.decodeRequest(legacyRequest(request)),
        )

        assertEquals(REQUEST_ID, decoded.value.requestId)
        assertEquals(REQUEST_ID, decoded.value.relationshipId)
    }

    @Test
    fun `all server outcomes use bounded response frames`() {
        val responses = listOf(
            FriendControlResponse.Received,
            FriendControlResponse.Accepted(
                "minekube://share/signed-host-card",
            ),
            FriendControlResponse.Declined,
            FriendControlResponse.TimedOut,
            FriendControlResponse.Invalid,
            FriendControlResponse.Removed,
            FriendControlResponse.Activity(
                FriendActivity(
                    FriendActivityKind.PLAYING_SERVER,
                    "Hypixel",
                    compatibility = CompatibilityProfile(
                        minecraftVersion = "1.21.1",
                        loader = ModLoader.FABRIC,
                        requiredMods = listOf(
                            RequiredMod("fabric-api", "1.0"),
                        ),
                        pack = PackReference(
                            platform = PackPlatform.MODRINTH,
                            projectId = "example-pack",
                            versionId = "v1",
                            url = "https://modrinth.com/modpack/example-pack/version/v1",
                        ),
                    ),
                ),
            ),
            FriendControlResponse.JoinAccepted("mc.hypixel.net"),
            FriendControlResponse.SharedWorldJoinAccepted,
        )

        responses.forEach { response ->
            val encoded = FriendControlWire.encodeResponse(response)
            val decoded = assertIs<
                FriendControlDecode.Decoded<FriendControlResponse>
                >(FriendControlWire.decodeResponse(encoded))
            assertEquals(response, decoded.value)
            assertEquals(encoded.size, decoded.consumedBytes)
        }
    }

    @Test
    fun `activity and join requests round trip without exposing a server address`() {
        val activity = FriendActivityRequest(REQUEST_ID)
        val join = FriendJoinRequest(
            requestId = REQUEST_ID,
            playerName = "RoboFlax2",
            playerUuid = PLAYER_UUID,
        )

        assertEquals(
            activity,
            assertIs<FriendControlDecode.Decoded<FriendActivityRequest>>(
                FriendControlWire.decodeActivityRequest(
                    FriendControlWire.encodeActivityRequest(activity),
                ),
            ).value,
        )
        assertEquals(
            join,
            assertIs<FriendControlDecode.Decoded<FriendJoinRequest>>(
                FriendControlWire.decodeJoinRequest(
                    FriendControlWire.encodeJoinRequest(join),
                ),
            ).value,
        )
    }

    @Test
    fun `removal command round trips with a stable operation id`() {
        val removal = FriendRemovalRequest(REQUEST_ID)

        val encoded = FriendControlWire.encodeRemoval(removal)
        val decoded = assertIs<
            FriendControlDecode.Decoded<FriendRemovalRequest>
            >(FriendControlWire.decodeRemoval(encoded))

        assertEquals(removal, decoded.value)
        assertEquals(encoded.size, decoded.consumedBytes)
        assertEquals(
            FriendControlMessageKind.REMOVAL,
            assertIs<FriendControlDecode.Decoded<FriendControlMessageKind>>(
                FriendControlWire.inspectControlMessage(encoded),
            ).value,
        )
    }

    @Test
    fun `new decoder accepts legacy removal frames with operation id fallback`() {
        val decoded = assertIs<FriendControlDecode.Decoded<FriendRemovalRequest>>(
            FriendControlWire.decodeRemoval(legacyRemoval(REQUEST_ID)),
        )

        assertEquals(REQUEST_ID, decoded.value.operationId)
        assertEquals(REQUEST_ID, decoded.value.relationshipId)
    }

    @Test
    fun `partial and oversized control frames are never accepted`() {
        val encoded = FriendControlWire.encodeRequest(
            request = FriendControlRequest(
                requestId = REQUEST_ID,
                displayName = "bob",
                invitation = "minekube://share/signed-bob-card",
            ),
        )

        assertIs<FriendControlDecode.Incomplete>(
            FriendControlWire.decodeRequest(encoded.copyOf(encoded.size - 1)),
        )
        assertIs<FriendControlDecode.Invalid>(
            FriendControlWire.decodeRequest(
                encoded + ByteArray(FriendControlWire.MAX_REQUEST_BYTES),
            ),
        )
    }

    private companion object {
        val REQUEST_ID: UUID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val PLAYER_UUID: UUID =
            UUID.fromString("11111111-2222-3333-4444-555555555555")

        fun legacyRequest(request: FriendControlRequest): ByteArray {
            val current = FriendControlWire.encodeRequest(request)
            val bodyStart = varIntLength(current)
            val body = current.copyOfRange(bodyStart, current.size)
            val packetIdLength = varIntLength(body)
            val legacyBody = body.copyOfRange(0, packetIdLength + 16) +
                body.copyOfRange(packetIdLength + 32, body.size)
            return frame(legacyBody)
        }

        fun legacyRemoval(operationId: UUID): ByteArray {
            val current = FriendControlWire.encodeRemoval(
                FriendRemovalRequest(operationId),
            )
            val bodyStart = varIntLength(current)
            val body = current.copyOfRange(bodyStart, current.size)
            val packetIdLength = varIntLength(body)
            return frame(body.copyOfRange(0, packetIdLength + 16))
        }

        fun frame(body: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            writeVarInt(body.size)
            write(body)
        }.toByteArray()

        fun varIntLength(bytes: ByteArray): Int {
            var index = 0
            while (bytes[index++].toInt() and 0x80 != 0) Unit
            return index
        }

        fun ByteArrayOutputStream.writeVarInt(value: Int) {
            var remaining = value
            do {
                var byte = remaining and 0x7f
                remaining = remaining ushr 7
                if (remaining != 0) byte = byte or 0x80
                write(byte)
            } while (remaining != 0)
        }
    }
}
