package com.minekube.connect.share.direct

import arrow.core.Either
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShareInviteCodecTest {
    @Test
    fun `signed invitation round trips without leaking its capability`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val invite = payload().signWith(keyPair)

        val uri = ShareInviteCodec.encode(invite)
        val decoded = ShareInviteCodec.decode(
            uri = uri,
            now = Instant.ofEpochMilli(NOW),
        )

        assertEquals(invite, assertIs<Either.Right<SignedShareInvite>>(decoded).value)
        assertTrue(uri.startsWith("minekube://share/"))
        assertFalse(invite.toString().contains(CAPABILITY))
        assertFalse(decoded.toString().contains(CAPABILITY))
    }

    @Test
    fun `tampering is rejected before dialing`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val uri = ShareInviteCodec.encode(payload().signWith(keyPair))
        val encoded = uri.substringAfterLast('/')
        val bytes = Base64.getUrlDecoder().decode(encoded)
        bytes[bytes.lastIndex - 4] = (bytes[bytes.lastIndex - 4].toInt() xor 1).toByte()

        val decoded = ShareInviteCodec.decode(
            "minekube://share/${Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}",
            Instant.ofEpochMilli(NOW),
        )

        assertIs<Either.Left<ShareInviteError.InvalidSignature>>(decoded)
    }

    @Test
    fun `new signed invitations carry the sender username`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val invite = payload(displayName = "RoboFlax2").signWith(keyPair)

        val decoded = assertIs<Either.Right<SignedShareInvite>>(
            ShareInviteCodec.decode(
                ShareInviteCodec.encode(invite),
                Instant.ofEpochMilli(NOW),
            ),
        ).value

        assertEquals("RoboFlax2", decoded.payload.displayName)
    }

    @Test
    fun `legacy version one invitations remain readable without a username`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val invite = payload(
            wireVersion = 1,
            displayName = null,
        ).signWith(keyPair)

        val decoded = assertIs<Either.Right<SignedShareInvite>>(
            ShareInviteCodec.decode(
                ShareInviteCodec.encode(invite),
                Instant.ofEpochMilli(NOW),
            ),
        ).value

        assertEquals(1, decoded.payload.wireVersion)
        assertEquals(null, decoded.payload.displayName)
    }

    @Test
    fun `expired and unsupported invitations are rejected`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val expired = payload(expiresAt = NOW - 1).signWith(keyPair)
        val unsupported = payload(wireVersion = ShareInviteCodec.WIRE_VERSION + 1)
            .signWith(keyPair)

        assertIs<Either.Left<ShareInviteError.Expired>>(
            ShareInviteCodec.decode(
                ShareInviteCodec.encode(expired),
                Instant.ofEpochMilli(NOW),
            ),
        )
        assertIs<Either.Left<ShareInviteError.UnsupportedVersion>>(
            ShareInviteCodec.decode(
                ShareInviteCodec.encode(unsupported),
                Instant.ofEpochMilli(NOW),
            ),
        )
    }

    @Test
    fun `direct invitations reject circuit relay candidates`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val relayed = payload(
            directCandidates = listOf(
                "/ip4/203.0.113.8/tcp/4001/p2p/QmRelay/p2p-circuit/p2p/QmHost",
            ),
        ).signWith(keyPair)

        val decoded = ShareInviteCodec.decode(
            ShareInviteCodec.encode(relayed),
            Instant.ofEpochMilli(NOW),
        )

        assertIs<Either.Left<ShareInviteError.RelayCandidateForbidden>>(decoded)
    }

    @Test
    fun `direct candidates must name the signed host peer`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val mismatched = payload(
            directCandidates = listOf(
                "/ip4/203.0.113.8/tcp/4001/p2p/12D3KooWAttacker",
            ),
        ).signWith(keyPair)

        val decoded = ShareInviteCodec.decode(
            ShareInviteCodec.encode(mismatched),
            Instant.ofEpochMilli(NOW),
        )

        assertIs<Either.Left<ShareInviteError.PeerMismatch>>(decoded)
    }

    private fun payload(
        wireVersion: Int = ShareInviteCodec.WIRE_VERSION,
        expiresAt: Long = NOW + 60_000,
        displayName: String? = null,
        directCandidates: List<String> = listOf(
            "/ip6/2001:db8::8/tcp/4001/p2p/12D3KooWHost",
        ),
    ) = ShareInvitePayload(
        wireVersion = wireVersion,
        shareId = UUID.fromString("9e511188-31a9-43ac-9107-29d94410d554"),
        expiresAtEpochMillis = expiresAt,
        connectAddress = "amber-fox.play.minekube.net",
        peerId = "12D3KooWHost",
        internetDirectEnabled = true,
        directCandidates = directCandidates,
        capability = CAPABILITY,
        displayName = displayName,
    )

    private fun ShareInvitePayload.signWith(keyPair: KeyPair): SignedShareInvite {
        val publicKey = keyPair.public.encoded
        val unsigned = ShareInviteCodec.unsignedBytes(this, publicKey)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(unsigned)
        return SignedShareInvite(
            payload = this,
            publicKey = publicKey,
            signature = signer.sign(),
        )
    }

    private companion object {
        const val NOW = 1_785_384_000_000
        const val CAPABILITY = "capability-secret-123456789"
    }
}
