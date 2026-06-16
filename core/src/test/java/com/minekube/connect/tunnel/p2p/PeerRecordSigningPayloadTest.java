package com.minekube.connect.tunnel.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import minekube.connect.v1alpha1.ConnectLibp2P.EndpointPeerRecord;
import minekube.connect.v1alpha1.ConnectLibp2P.OfflineMode;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerCapacity;
import org.junit.jupiter.api.Test;

class PeerRecordSigningPayloadTest {

    @Test
    void matchesGoDeterministicProtoPayload() {
        EndpointPeerRecord record = EndpointPeerRecord.newBuilder()
                .setVersion(1)
                .setEndpoint("endpoint")
                .setEndpointHash("hash")
                .setEndpointId("endpoint-id")
                .setEndpointInstanceId("instance")
                .setEndpointPeerId("peer")
                .setEndpointPublicKey("pub")
                .setPublisherId("publisher")
                .setPublisherPeerId("publisher-peer")
                .setRegion("local")
                .addAddrs("/ip4/127.0.0.1/tcp/1/p2p/peer")
                .addDirectAddrs("/ip4/127.0.0.1/tcp/2/p2p/peer")
                .addCapabilities("session")
                .setCapacity(PeerCapacity.newBuilder().setMaxSessions(10).setActiveSessions(2))
                .setOfflineMode(OfflineMode.OFFLINE_MODE_ALLOWED)
                .setSequence(7)
                .setIssuedAtUnixMs(1000)
                .setRenewedAtUnixMs(1100)
                .setExpiresAtUnixMs(2000)
                .setNonce(ByteString.copyFromUtf8("nonce"))
                .build();

        assertEquals("08011208656e64706f696e741a0468617368220b656e64706f696e742d69642a08696e7374616e63653204706565723a0370756242097075626c69736865724a0e7075626c69736865722d7065657252056c6f63616c5a1d2f6970342f3132372e302e302e312f7463702f312f7032702f70656572621d2f6970342f3132372e302e302e312f7463702f322f7032702f706565726a0773657373696f6e7204080a100278018001078801e8079001cc089801d00fa201056e6f6e6365",
                hex(PeerRecordSigningPayload.bytes(record)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }
}
