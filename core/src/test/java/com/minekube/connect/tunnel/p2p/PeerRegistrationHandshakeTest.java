package com.minekube.connect.tunnel.p2p;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.PubKey;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import minekube.connect.v1alpha1.ConnectLibp2P.EndpointAuthType;
import minekube.connect.v1alpha1.ConnectLibp2P.EndpointPeerRecord;
import minekube.connect.v1alpha1.ConnectLibp2P.OfflineMode;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerCapacity;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerRegisterChallenge;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerRegisterCommit;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerRegisterInit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeerRegistrationHandshakeTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsInitAndSignedCommitFromChallenge() throws Exception {
        EndpointPeerIdentity identity = EndpointPeerIdentity.loadOrCreate(tempDir.resolve("libp2p-identity.key"));
        PeerRegistrationHandshake handshake = new PeerRegistrationHandshake(
                identity,
                "endpoint",
                "token",
                "instance",
                Collections.singletonList("parent"),
                OfflineMode.OFFLINE_MODE_ALLOWED,
                EndpointAuthType.ENDPOINT_AUTH_TYPE_PROXIED,
                Arrays.asList("session", "status"),
                PeerCapacity.newBuilder().setMaxSessions(100).setActiveSessions(3).build());

        String relayAddr = "/dns4/relay.example/tcp/4001/p2p/relay/p2p-circuit/p2p/" + identity.peerId();
        PeerRegisterInit init = handshake.init(Collections.singletonList(relayAddr));

        assertEquals("endpoint", init.getEndpoint());
        assertEquals("token", init.getToken());
        assertEquals("instance", init.getEndpointInstanceId());
        assertEquals(identity.peerId(), init.getEndpointPeerId());
        assertEquals(identity.publicKeyBase64(), init.getEndpointPublicKey());
        assertEquals(OfflineMode.OFFLINE_MODE_ALLOWED, init.getOfflineMode());
        assertEquals(EndpointAuthType.ENDPOINT_AUTH_TYPE_PROXIED, init.getAuthType());
        assertEquals(Arrays.asList("session", "status", "bedrock-identity-v1"), init.getCapabilitiesList());

        PeerRegisterChallenge challenge = PeerRegisterChallenge.newBuilder()
                .setEndpointId("endpoint-id")
                .setEndpointHash("endpoint-hash")
                .setPublisherId("publisher")
                .setPublisherPeerId("publisher-peer")
                .setRegion("local")
                .setKvTtlMs(45_000)
                .setNonce(ByteString.copyFromUtf8("nonce"))
                .build();
        PeerRegisterCommit commit = handshake.commit(challenge, init.getObservedAddrsList(), 7, 1_000);

        EndpointPeerRecord record = commit.getRecord();
        assertEquals("endpoint", record.getEndpoint());
        assertEquals("endpoint-hash", record.getEndpointHash());
        assertEquals("endpoint-id", record.getEndpointId());
        assertEquals("publisher", record.getPublisherId());
        assertEquals("publisher-peer", record.getPublisherPeerId());
        assertEquals("local", record.getRegion());
        assertEquals(Arrays.asList("session", "status", "bedrock-identity-v1"), record.getCapabilitiesList());
        assertEquals(EndpointAuthType.ENDPOINT_AUTH_TYPE_PROXIED, record.getAuthType());
        assertEquals(init.getObservedAddrsList(), record.getAddrsList());
        assertEquals(Collections.emptyList(), record.getDirectAddrsList());
        assertArrayEquals(challenge.getNonce().toByteArray(), record.getNonce().toByteArray());
        assertEquals(7, record.getSequence());
        assertEquals(1_000, record.getIssuedAtUnixMs());
        assertEquals(1_000, record.getRenewedAtUnixMs());
        assertEquals(46_000, record.getExpiresAtUnixMs());

        PubKey publicKey = KeyKt.unmarshalPublicKey(Base64.getDecoder().decode(identity.publicKeyBase64()));
        assertTrue(publicKey.verify(
                PeerRecordSigningPayload.bytes(record),
                commit.getSignature().toByteArray()));
    }

    @Test
    void commitUsesFreshCapacityFromSupplier() throws Exception {
        EndpointPeerIdentity identity = EndpointPeerIdentity.loadOrCreate(tempDir.resolve("libp2p-identity.key"));
        AtomicInteger activeSessions = new AtomicInteger(1);
        PeerRegistrationHandshake handshake = new PeerRegistrationHandshake(
                identity,
                "endpoint",
                "token",
                "instance",
                Collections.emptyList(),
                OfflineMode.OFFLINE_MODE_ALLOWED,
                Arrays.asList("session", "status"),
                () -> PeerCapacity.newBuilder()
                        .setMaxSessions(100)
                        .setActiveSessions(activeSessions.get())
                        .build());
        PeerRegisterChallenge challenge = PeerRegisterChallenge.newBuilder()
                .setEndpointId("endpoint-id")
                .setEndpointHash("endpoint-hash")
                .setPublisherId("publisher")
                .setPublisherPeerId("publisher-peer")
                .setRegion("local")
                .setKvTtlMs(45_000)
                .setNonce(ByteString.copyFromUtf8("nonce"))
                .build();

        PeerRegisterCommit first = handshake.commit(challenge, Collections.singletonList(
                "/dns4/relay.example/tcp/4001/p2p/relay/p2p-circuit/p2p/" + identity.peerId()), 1, 1_000);
        activeSessions.set(7);
        PeerRegisterCommit second = handshake.commit(challenge, Collections.singletonList(
                "/dns4/relay.example/tcp/4001/p2p/relay/p2p-circuit/p2p/" + identity.peerId()), 2, 2_000);

        assertEquals(1, first.getRecord().getCapacity().getActiveSessions());
        assertEquals(7, second.getRecord().getCapacity().getActiveSessions());
    }

    @Test
    void commitSignsReservedChallengeRelayAddrsInsteadOfObservedBootstrapAddrs() throws Exception {
        EndpointPeerIdentity identity = EndpointPeerIdentity.loadOrCreate(tempDir.resolve("libp2p-identity.key"));
        String relayPeerId = "12D3KooWCqs14yyxXW5rosC5CJ9tmNsRvQ97KKUx5HzGKK1pyVsN";
        PeerRegistrationHandshake handshake = new PeerRegistrationHandshake(
                identity,
                "endpoint",
                "token",
                "instance",
                Collections.emptyList(),
                OfflineMode.OFFLINE_MODE_ALLOWED,
                Arrays.asList("session", "status"),
                PeerCapacity.newBuilder().setMaxSessions(100).setActiveSessions(3).build());
        PeerRegisterChallenge challenge = PeerRegisterChallenge.newBuilder()
                .setEndpointId("endpoint-id")
                .setEndpointHash("endpoint-hash")
                .setPublisherId("publisher")
                .setPublisherPeerId(relayPeerId)
                .setRegion("cdg")
                .addRelayAddrs("/dns6/6832e0da270568.vm.connect-proxy-staging.internal/tcp/4001/p2p/" + relayPeerId)
                .setKvTtlMs(45_000)
                .setNonce(ByteString.copyFromUtf8("nonce"))
                .build();

        PeerRegisterCommit commit = handshake.commit(challenge, Collections.singletonList(
                "/dns4/connect-proxy-staging.fly.dev/tcp/4001/p2p/" + relayPeerId + "/p2p-circuit/p2p/"
                        + identity.peerId()), 1, 1_000);

        assertEquals(Collections.singletonList(
                        "/dns6/6832e0da270568.vm.connect-proxy-staging.internal/tcp/4001/p2p/" + relayPeerId
                                + "/p2p-circuit/p2p/" + identity.peerId()),
                commit.getRecord().getAddrsList());
    }

    @Test
    void commitDoesNotIncludeAdditionalUnchallengedRelayCircuitAddrs() throws Exception {
        EndpointPeerIdentity identity = EndpointPeerIdentity.loadOrCreate(tempDir.resolve("libp2p-identity.key"));
        String publisherRelayPeerId = "12D3KooWCqs14yyxXW5rosC5CJ9tmNsRvQ97KKUx5HzGKK1pyVsN";
        String bedrockRelayPeerId = "12D3KooWGHadkiFkRv4oq1UreQe9b1XPqPoKs9NbZrb5aq1QGttN";
        PeerRegistrationHandshake handshake = new PeerRegistrationHandshake(
                identity,
                "endpoint",
                "token",
                "instance",
                Collections.emptyList(),
                OfflineMode.OFFLINE_MODE_ALLOWED,
                Arrays.asList("session", "status"),
                PeerCapacity.newBuilder().setMaxSessions(100).setActiveSessions(3).build());
        PeerRegisterChallenge challenge = PeerRegisterChallenge.newBuilder()
                .setEndpointId("endpoint-id")
                .setEndpointHash("endpoint-hash")
                .setPublisherId("publisher")
                .setPublisherPeerId(publisherRelayPeerId)
                .setRegion("cdg")
                .addRelayAddrs("/dns6/6832e0da270568.vm.connect-proxy-staging.internal/tcp/4001/p2p/" + publisherRelayPeerId)
                .setKvTtlMs(45_000)
                .setNonce(ByteString.copyFromUtf8("nonce"))
                .build();
        String bedrockCircuitAddr = "/dns6/e82723db106108.vm.connect-proxy-staging.internal/tcp/4001/p2p/"
                + bedrockRelayPeerId + "/p2p-circuit/p2p/" + identity.peerId();

        PeerRegisterCommit commit = handshake.commit(challenge, Arrays.asList(
                "/dns4/connect-proxy-staging.fly.dev/tcp/4001/p2p/" + publisherRelayPeerId
                        + "/p2p-circuit/p2p/" + identity.peerId(),
                bedrockCircuitAddr), 1, 1_000);

        assertEquals(Collections.singletonList(
                        "/dns6/6832e0da270568.vm.connect-proxy-staging.internal/tcp/4001/p2p/"
                                + publisherRelayPeerId + "/p2p-circuit/p2p/" + identity.peerId()),
                commit.getRecord().getAddrsList());
    }

    @Test
    void commitDoesNotAdvertiseChallengeRelayAddrsWithoutReservation() throws Exception {
        EndpointPeerIdentity identity = EndpointPeerIdentity.loadOrCreate(tempDir.resolve("libp2p-identity.key"));
        String relayPeerId = "12D3KooWCqs14yyxXW5rosC5CJ9tmNsRvQ97KKUx5HzGKK1pyVsN";
        PeerRegistrationHandshake handshake = new PeerRegistrationHandshake(
                identity,
                "endpoint",
                "token",
                "instance",
                Collections.emptyList(),
                OfflineMode.OFFLINE_MODE_ALLOWED,
                Arrays.asList("session", "status"),
                PeerCapacity.newBuilder().setMaxSessions(100).setActiveSessions(3).build());
        PeerRegisterChallenge challenge = PeerRegisterChallenge.newBuilder()
                .setEndpointId("endpoint-id")
                .setEndpointHash("endpoint-hash")
                .setPublisherId("publisher")
                .setPublisherPeerId(relayPeerId)
                .setRegion("cdg")
                .addRelayAddrs("/dns6/6832e0da270568.vm.connect-proxy-staging.internal/tcp/4001/p2p/" + relayPeerId)
                .setKvTtlMs(45_000)
                .setNonce(ByteString.copyFromUtf8("nonce"))
                .build();

        PeerRegisterCommit commit = handshake.commit(challenge, Collections.emptyList(), 1, 1_000);

        assertEquals(Collections.emptyList(), commit.getRecord().getAddrsList());
    }
}
