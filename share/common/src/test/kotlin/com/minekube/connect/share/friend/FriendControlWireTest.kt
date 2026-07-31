package com.minekube.connect.share.friend

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
                ),
            ),
            FriendControlResponse.JoinAccepted("mc.hypixel.net"),
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
        val join = FriendJoinRequest(REQUEST_ID)

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
    }
}
