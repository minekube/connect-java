package com.minekube.connect.tunnel.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.rpc.Code;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.bedrock.VerifiedBedrockIdentityRegistry;
import com.minekube.connect.tunnel.Tunneler;
import com.minekube.connect.watch.SessionProposal;
import io.libp2p.core.Stream;
import io.libp2p.core.PeerId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import minekube.connect.v1alpha1.ConnectLibp2P.SessionAccepted;
import minekube.connect.v1alpha1.ConnectLibp2P.SessionAuthentication;
import minekube.connect.v1alpha1.ConnectLibp2P.SessionGameProfile;
import minekube.connect.v1alpha1.ConnectLibp2P.SessionGameProfileProperty;
import minekube.connect.v1alpha1.ConnectLibp2P.SessionOffer;
import minekube.connect.v1alpha1.ConnectLibp2P.SessionPlayer;
import minekube.connect.v1alpha1.ConnectLibp2P.SessionResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class Libp2pSessionResponderTest {
    private static final String ALLOWED_PEER = "12D3KooWNXa3WQenRYKVJCHxcadnp3JPcxRALCqk97qpMiqAG1tt";
    private static final String OTHER_PEER = "12D3KooWEzZpASrUwA3s8CM3UCDCCYjQzfh91ZyJnxRqaZ9xTi31";

    @Test
    void privateOfferUsesCoordinatorTokenAndSanitizedProposal() throws Exception {
        Stream stream = mock(Stream.class);
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(registry);
        AtomicReference<SessionProposal> proposalRef = new AtomicReference<>();
        AtomicReference<ConnectPlayer> playerRef = new AtomicReference<>();
        SessionOffer source = offer();
        SessionOffer privateOffer = source.toBuilder()
                .setPlayer(source.getPlayer().toBuilder()
                        .setProfile(source.getPlayer().getProfile().toBuilder()
                                .addProperties(SessionGameProfileProperty.newBuilder()
                                        .setName("minekube:bedrock_identity")
                                        .setValue("signed-envelope-replay-nonce-a"))))
                .build();
        Libp2pSessionResponder responder = new Libp2pSessionResponder(
                "endpoint",
                System::currentTimeMillis,
                Collections.emptySet(),
                (proposal, tunneler) -> {
                    proposalRef.set(proposal);
                    playerRef.set(coordinator.stage(proposal));
                    tunneler.tunnel(proposal.getSession(), new NoopHandler());
                },
                coordinator);

        try {
            responder.handleOffer(stream, privateOffer);

            assertNotNull(proposalRef.get().getAdmissionToken());
            assertFalse(proposalRef.get().getSession().toString()
                    .contains("signed-envelope-replay-nonce-a"));
            assertFalse(playerRef.get().getGameProfile().toString()
                    .contains("signed-envelope-replay-nonce-a"));
            SessionResponse response = writtenResponse(stream);
            assertTrue(response.hasAccepted());
            System.out.println("LIBP2P admission: session=" + response.getSessionId()
                    + ", result=ACCEPTED, sameStream=" + response.getAccepted().getSameStreamData()
                    + ", token=opaque, proposalPrivateEnvelope=false, stagedPlayerPrivateEnvelope=false");
        } finally {
            coordinator.close();
        }
    }

    @Test
    void sendsAcceptedWhenLocalSessionOpensSameStreamTunnel() throws Exception {
        Stream stream = mock(Stream.class);
        AtomicReference<SessionProposal> proposalRef = new AtomicReference<>();
        Libp2pSessionResponder responder = new Libp2pSessionResponder((proposal, tunneler) -> {
            proposalRef.set(proposal);
            tunneler.tunnel(proposal.getSession(), new NoopHandler());
        });

        responder.handleOffer(stream, offer());

        assertEquals("session-1", proposalRef.get().getSession().getId());
        assertEquals("endpoint-id", proposalRef.get().getEndpointId());
        assertEquals("org-id", proposalRef.get().getEndpointOrgId());
        ArgumentCaptor<Object> outbound = ArgumentCaptor.forClass(Object.class);
        verify(stream).writeAndFlush(outbound.capture());
        SessionResponse response = P2PFrameCodec.read(
                new ByteBufInputStream((ByteBuf) outbound.getValue()),
                SessionResponse.parser(),
                P2PFrameCodec.MAX_CONTROL_FRAME_SIZE);
        assertTrue(response.hasAccepted(), response.toString());
        SessionAccepted accepted = response.getAccepted();
        assertTrue(accepted.getSameStreamData());
    }

    @Test
    void installedHandlerReadsOfferFrame() throws Exception {
        Stream stream = mock(Stream.class);
        AtomicReference<SessionProposal> proposalRef = new AtomicReference<>();
        Libp2pSessionResponder responder = new Libp2pSessionResponder((proposal, tunneler) -> {
            proposalRef.set(proposal);
            tunneler.tunnel(proposal.getSession(), new NoopHandler());
        });

        responder.install(stream);

        ArgumentCaptor<ChannelHandler> handler = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(stream, times(2)).pushHandler(handler.capture());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        P2PFrameCodec.write(out, offer());
        EmbeddedChannel channel = new EmbeddedChannel(handler.getAllValues().toArray(ChannelHandler[]::new));
        channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(out.toByteArray()));

        assertEquals("session-1", proposalRef.get().getSession().getId());
        assertNull(channel.pipeline().get(P2PFrameDecoder.class), "control decoder must be removed before tunnel bytes");
    }

    @Test
    void installedHandlerReadsSplitOfferFrame() throws Exception {
        Stream stream = mock(Stream.class);
        AtomicReference<SessionProposal> proposalRef = new AtomicReference<>();
        Libp2pSessionResponder responder = new Libp2pSessionResponder((proposal, tunneler) -> {
            proposalRef.set(proposal);
            tunneler.tunnel(proposal.getSession(), new NoopHandler());
        });

        responder.install(stream);

        ArgumentCaptor<ChannelHandler> handler = ArgumentCaptor.forClass(ChannelHandler.class);
        verify(stream, times(2)).pushHandler(handler.capture());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        P2PFrameCodec.write(out, offer());
        byte[] frame = out.toByteArray();
        EmbeddedChannel channel = new EmbeddedChannel(handler.getAllValues().toArray(ChannelHandler[]::new));
        channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(frame, 0, 2));
        channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(frame, 2, frame.length - 2));

        assertEquals("session-1", proposalRef.get().getSession().getId());
    }

    @Test
    void rejectsOfferForWrongEndpoint() throws Exception {
        Stream stream = mock(Stream.class);
        Libp2pSessionResponder responder = new Libp2pSessionResponder("endpoint", () -> 1_000, (proposal, tunneler) -> {
            throw new AssertionError("starter should not be called");
        });

        responder.handleOffer(stream, offer().toBuilder().setEndpoint("other").build());

        SessionResponse response = writtenResponse(stream);
        assertTrue(response.hasRejected(), response.toString());
        assertEquals(Code.INVALID_ARGUMENT_VALUE, response.getRejected().getReason().getCode());
    }

    @Test
    void rejectsExpiredOffer() throws Exception {
        Stream stream = mock(Stream.class);
        Libp2pSessionResponder responder = new Libp2pSessionResponder("endpoint", () -> 2_000, (proposal, tunneler) -> {
            throw new AssertionError("starter should not be called");
        });

        responder.handleOffer(stream, offer().toBuilder().setDeadlineUnixMs(1_999).build());

        SessionResponse response = writtenResponse(stream);
        assertTrue(response.hasRejected(), response.toString());
        assertEquals(Code.DEADLINE_EXCEEDED_VALUE, response.getRejected().getReason().getCode());
    }

    @Test
    void rejectsOfferFromUnauthorizedConnectEdgePeer() throws Exception {
        Stream stream = mock(Stream.class);
        when(stream.remotePeerId()).thenReturn(PeerId.fromBase58(OTHER_PEER));
        Libp2pSessionResponder responder = new Libp2pSessionResponder(
                "endpoint",
                () -> 1_000,
                Collections.singleton(ALLOWED_PEER),
                (proposal, tunneler) -> {
                    throw new AssertionError("starter should not be called");
                });

        responder.handleOffer(stream, offer());

        SessionResponse response = writtenResponse(stream);
        assertTrue(response.hasRejected(), response.toString());
        assertEquals(Code.PERMISSION_DENIED_VALUE, response.getRejected().getReason().getCode());
    }

    @Test
    void starterFailureCancelsPrivateAdmissionImmediately() throws Exception {
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(
                new VerifiedBedrockIdentityRegistry());
        SessionOffer source = offer();
        SessionOffer privateOffer = source.toBuilder()
                .setPlayer(source.getPlayer().toBuilder()
                        .setProfile(source.getPlayer().getProfile().toBuilder()
                                .addProperties(SessionGameProfileProperty.newBuilder()
                                        .setName("minekube:bedrock_identity")
                                        .setValue("signed-envelope"))))
                .build();
        Libp2pSessionResponder responder = new Libp2pSessionResponder(
                "endpoint",
                System::currentTimeMillis,
                Collections.emptySet(),
                (proposal, tunneler) -> {
                    throw new IllegalStateException("setup failed");
                },
                coordinator);

        try {
            responder.handleOffer(mock(Stream.class), privateOffer);

            Field admissions = BedrockAdmissionCoordinator.class.getDeclaredField("admissions");
            admissions.setAccessible(true);
            assertTrue(((Map<?, ?>) admissions.get(coordinator)).isEmpty());
        } finally {
            coordinator.close();
        }
    }

    private static SessionOffer offer() {
        return SessionOffer.newBuilder()
                .setSessionId("session-1")
                .setEndpoint("endpoint")
                .setEndpointId("endpoint-id")
                .setEndpointOrgId("org-id")
                .setDeadlineUnixMs(System.currentTimeMillis() + 60_000)
                .setPlayer(SessionPlayer.newBuilder()
                        .setAddr("127.0.0.1")
                        .setProfile(SessionGameProfile.newBuilder()
                                .setId("00000000-0000-0000-0000-000000000000")
                                .setName("Player")))
                .setAuth(SessionAuthentication.newBuilder().setPassthrough(false))
                .build();
    }

    private static SessionResponse writtenResponse(Stream stream) throws Exception {
        ArgumentCaptor<Object> outbound = ArgumentCaptor.forClass(Object.class);
        verify(stream).writeAndFlush(outbound.capture());
        return P2PFrameCodec.read(
                new ByteBufInputStream((ByteBuf) outbound.getValue()),
                SessionResponse.parser(),
                P2PFrameCodec.MAX_CONTROL_FRAME_SIZE);
    }

    private static final class NoopHandler implements com.minekube.connect.tunnel.TunnelConn.Handler {
        @Override
        public void onReceive(byte[] data) {
        }

        @Override
        public void onError(Throwable t) {
        }
    }
}
