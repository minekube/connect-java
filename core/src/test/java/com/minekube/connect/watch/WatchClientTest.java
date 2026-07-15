package com.minekube.connect.watch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.bedrock.BedrockIdentityKeyProvider;
import com.minekube.connect.bedrock.BedrockIdentityReadiness;
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
}
