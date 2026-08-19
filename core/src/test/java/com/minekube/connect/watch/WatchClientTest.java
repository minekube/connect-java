package com.minekube.connect.watch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.bedrock.BedrockIdentityKeyProvider;
import com.minekube.connect.bedrock.BedrockIdentityReadiness;
import com.minekube.connect.bedrock.BedrockPrincipalReadiness;
import com.minekube.connect.bedrock.VerifiedBedrockIdentityRegistry;
import com.minekube.connect.config.ConnectConfig;
import java.util.concurrent.atomic.AtomicReference;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Authentication;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfile;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfileProperty;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionProtocol;
import minekube.connect.v1alpha1.WatchServiceOuterClass.WatchResponse;
import minekube.connect.v1alpha1.WatchServiceOuterClass.ReadinessChallenge;
import minekube.connect.v1alpha1.WatchServiceOuterClass.TunnelTransport;
import minekube.connect.v1alpha1.WatchServiceOuterClass.WatchRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WatchClientTest {
    @Test
    void privateWatchSessionUsesCoordinatorTokenAndSanitizedProposal() {
        ConnectConfig config = new ConnectConfig();
        OkHttpClient httpClient = mock(OkHttpClient.class);
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(registry);
        WatchClient client = new WatchClient(
                httpClient,
                config,
                new BedrockIdentityReadiness(
                        config,
                        new BedrockIdentityKeyProvider(config, new OkHttpClient())),
                coordinator);
        AtomicReference<SessionProposal> proposalRef = new AtomicReference<>();
        Watcher watcher = new Watcher() {
            @Override public void onOpen(WatchBootstrap bootstrap) { }
            @Override public void onProposal(SessionProposal proposal) { proposalRef.set(proposal); }
            @Override public void onCompleted() { }
            @Override public void onError(Throwable throwable) { }
        };

        try {
            client.watch(watcher);
            ArgumentCaptor<WebSocketListener> listener = ArgumentCaptor.forClass(WebSocketListener.class);
            verify(httpClient).newWebSocket(any(Request.class), listener.capture());
            Session raw = Session.newBuilder()
                    .setId("session-1")
                    .setProtocol(SessionProtocol.SESSION_PROTOCOL_BEDROCK)
                    .setAuth(Authentication.getDefaultInstance())
                    .setPlayer(Player.newBuilder()
                            .setAddr("127.0.0.1")
                            .setProfile(GameProfile.newBuilder()
                                    .setId("f912bf90-8349-565f-9dc0-9891923c0cc3")
                                    .setName("BedrockSteve")
                                    .addProperties(GameProfileProperty.newBuilder()
                                            .setName("minekube:bedrock_identity")
                                            .setValue("signed-envelope-replay-nonce-a"))))
                    .build();

            listener.getValue().onMessage(
                    mock(WebSocket.class),
                    ByteString.of(WatchResponse.newBuilder().setSession(raw).build().toByteArray()));

            SessionProposal proposal = proposalRef.get();
            assertNotNull(proposal);
            assertNotNull(proposal.getAdmissionToken());
            assertFalse(proposal.getSession().toString().contains("signed-envelope-replay-nonce-a"));
            ConnectPlayer player = coordinator.stage(proposal);
            assertFalse(player.getGameProfile().toString().contains("signed-envelope-replay-nonce-a"));
            System.out.println("WATCH admission: session=" + proposal.getSession().getId() + ", token=opaque, "
                    + "proposalPrivateEnvelope=false, stagedPlayerPrivateEnvelope=false");
        } finally {
            coordinator.close();
        }
    }

    @Test
    void closedCoordinatorRejectsProposalInsteadOfFailingTheWatch() throws Exception {
        ConnectConfig config = new ConnectConfig();
        OkHttpClient httpClient = mock(OkHttpClient.class);
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(registry);
        WatchClient client = new WatchClient(
                httpClient,
                config,
                new BedrockIdentityReadiness(
                        config,
                        new BedrockIdentityKeyProvider(config, new OkHttpClient())),
                coordinator);
        java.util.concurrent.atomic.AtomicReference<SessionProposal> proposalRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> errorRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        Watcher watcher = new Watcher() {
            @Override public void onOpen(WatchBootstrap bootstrap) { }
            @Override public void onProposal(SessionProposal proposal) { proposalRef.set(proposal); }
            @Override public void onCompleted() { }
            @Override public void onError(Throwable throwable) { errorRef.set(throwable); }
        };

        try {
            coordinator.close(); // disable() closed the shared coordinator
            client.watch(watcher);
            ArgumentCaptor<WebSocketListener> listener =
                    ArgumentCaptor.forClass(WebSocketListener.class);
            verify(httpClient).newWebSocket(any(Request.class), listener.capture());
            WebSocket socket = mock(WebSocket.class);
            Session raw = Session.newBuilder()
                    .setId("session-closed-coordinator")
                    .setPlayer(Player.newBuilder()
                            .setAddr("127.0.0.1")
                            .setProfile(GameProfile.newBuilder()
                                    .setId("f912bf90-8349-565f-9dc0-9891923c0cc3")
                                    .setName("Player")))
                    .build();

            // A proposal into a closed coordinator must be rejected over the wire, not allowed to
            // escape and fail the WebSocket (which would put the watch into a reconnect loop).
            assertDoesNotThrow(() -> listener.getValue().onMessage(
                    socket, ByteString.of(WatchResponse.newBuilder()
                            .setSession(raw).build().toByteArray())));

            assertNull(proposalRef.get(), "a closed coordinator must not accept the proposal");
            assertNull(errorRef.get(), "a closed coordinator must not fail the watch stream");
            ArgumentCaptor<ByteString> sent = ArgumentCaptor.forClass(ByteString.class);
            verify(socket).send(sent.capture());
            WatchRequest request = WatchRequest.parseFrom(sent.getValue().toByteArray());
            assertTrue(request.hasSessionRejection());
            assertEquals("session-closed-coordinator", request.getSessionRejection().getId());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void defaultDisabledConfigurationDoesNotAdvertiseBedrockIdentity() {
        OkHttpClient httpClient = mock(OkHttpClient.class);
        WatchClient client = new WatchClient(httpClient, new ConnectConfig());

        client.watch(new Watcher() {
            @Override public void onOpen(WatchBootstrap bootstrap) { }
            @Override public void onProposal(SessionProposal proposal) { }
            @Override public void onCompleted() { }
            @Override public void onError(Throwable throwable) { }
        });

        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        verify(httpClient).newWebSocket(request.capture(), any(WebSocketListener.class));
        assertFalse(request.getValue().headers("Connect-Capabilities")
                .contains("bedrock-identity-v1"));
    }

    @Test
    void readinessChallengeIsAnsweredAndNeverDeliveredAsSession() {
        ConnectConfig config = new ConnectConfig();
        OkHttpClient httpClient = mock(OkHttpClient.class);
        WatchClient client = new WatchClient(httpClient, config);
        Watcher watcher = mock(Watcher.class);
        WebSocket socket = mock(WebSocket.class);

        client.watch(watcher);
        ArgumentCaptor<WebSocketListener> listener = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(httpClient).newWebSocket(any(Request.class), listener.capture());
        ReadinessChallenge challenge = ReadinessChallenge.newBuilder()
                .setRequestId("request")
                .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[16]))
                .setEndpointId("endpoint")
                .setOrganizationId("organization")
                .setConnectorInstanceId("instance")
                .setLeaseId("lease")
                .setTransport(TunnelTransport.Type.TYPE_WEBSOCKET)
                .setPolicyRevision(1)
                .setIssuedAtUnix(1)
                .setExpiresAtUnix(31)
                .build();

        listener.getValue().onMessage(socket, ByteString.of(WatchResponse.newBuilder()
                .setReadinessChallenge(challenge).build().toByteArray()));

        ArgumentCaptor<ByteString> response = ArgumentCaptor.forClass(ByteString.class);
        verify(socket).send(response.capture());
        WatchRequest request;
        try {
            request = WatchRequest.parseFrom(response.getValue().toByteArray());
        } catch (com.google.protobuf.InvalidProtocolBufferException error) {
            throw new AssertionError(error);
        }
        assertTrue(request.hasReadinessAttestation());
        verify(watcher, org.mockito.Mockito.never()).onProposal(any());
    }
}
