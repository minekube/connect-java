package com.minekube.connect.network.netty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.bedrock.VerifiedBedrockIdentityRegistry;
import com.minekube.connect.tunnel.Tunneler;
import com.minekube.connect.watch.SessionProposal;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Authentication;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfile;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfileProperty;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import org.junit.jupiter.api.Test;

class LocalSessionBedrockIdentityTest {
    @Test
    void discardsStagedAdmissionWhenSynchronousSetupFails() throws Exception {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(registry);
        Constructor<?> productionConstructor = Arrays.stream(LocalSession.class.getConstructors())
                .filter(constructor -> Arrays.equals(
                        constructor.getParameterTypes(),
                        new Class<?>[] {
                                ConnectLogger.class,
                                SimpleConnectApi.class,
                                Tunneler.class,
                                java.net.SocketAddress.class,
                                SessionProposal.class,
                                BedrockAdmissionCoordinator.class
                        }))
                .findFirst()
                .orElse(null);
        assertNotNull(productionConstructor);
        Session session = Session.newBuilder()
                .setId("session-1")
                .setAuth(Authentication.getDefaultInstance())
                .setPlayer(Player.newBuilder()
                        .setAddr("")
                        .setProfile(GameProfile.newBuilder()
                                .setId("00000000-0000-0000-0000-000000000001")
                                .setName("Player")
                                .addProperties(GameProfileProperty.newBuilder()
                                        .setName("minekube:bedrock_identity")
                                        .setValue("signed-envelope"))))
                .build();
        SessionProposal proposal = coordinator.proposal(session, reason -> {}, "", "");
        LocalSession localSession = (LocalSession) productionConstructor.newInstance(
                mock(ConnectLogger.class),
                new SimpleConnectApi(mock(ConnectLogger.class), registry),
                mock(Tunneler.class),
                new InetSocketAddress("127.0.0.1", 25565),
                proposal,
                coordinator);

        try {
            assertThrows(IllegalArgumentException.class, localSession::connect);

            Field admissions = BedrockAdmissionCoordinator.class.getDeclaredField("admissions");
            admissions.setAccessible(true);
            assertTrue(((Map<?, ?>) admissions.get(coordinator)).isEmpty());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void discardsSameUuidDisplacedAdmissionAfterConnect() throws Exception {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(registry);
        Session firstSession = privateSession("session-1");
        Session secondSession = privateSession("session-2");
        SessionProposal firstProposal = coordinator.proposal(firstSession, reason -> {}, "", "");
        SessionProposal secondProposal = coordinator.proposal(secondSession, reason -> {}, "", "");
        ConnectPlayer firstPlayer = coordinator.stage(firstProposal);
        TrackingApi api = new TrackingApi(registry, "session-2");
        api.addPlayer(firstPlayer);
        DefaultEventLoopGroup eventLoopGroup = new DefaultEventLoopGroup(1);
        AtomicReference<Channel> acceptedChannel = new AtomicReference<>();
        LocalAddress target = new LocalAddress("connect-replacement-" + UUID.randomUUID());
        Channel server = null;
        LocalSession.setPlatformEventLoopGroup(eventLoopGroup);
        try {
            server = new ServerBootstrap()
                    .group(eventLoopGroup)
                    .channel(LocalServerChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel channel) {
                            acceptedChannel.set(channel);
                        }
                    })
                    .bind(target)
                    .sync()
                    .channel();
            new LocalSession(
                    mock(ConnectLogger.class),
                    api,
                    mock(Tunneler.class),
                    target,
                    secondProposal,
                    coordinator).connect();

            assertTrue(api.awaitReplacement());

            Field admissions = BedrockAdmissionCoordinator.class.getDeclaredField("admissions");
            admissions.setAccessible(true);
            Map<?, ?> pending = (Map<?, ?>) admissions.get(coordinator);
            assertFalse(pending.containsKey(firstProposal.getAdmissionToken()));
            assertTrue(pending.containsKey(secondProposal.getAdmissionToken()));
        } finally {
            Channel child = acceptedChannel.get();
            if (child != null) {
                child.close().syncUninterruptibly();
            }
            if (server != null) {
                server.close().syncUninterruptibly();
            }
            LocalSession.setPlatformEventLoopGroup(null);
            eventLoopGroup.shutdownGracefully().syncUninterruptibly();
            coordinator.close();
        }
    }

    private static Session privateSession(String sessionId) {
        return Session.newBuilder()
                .setId(sessionId)
                .setAuth(Authentication.getDefaultInstance())
                .setPlayer(Player.newBuilder()
                        .setAddr("127.0.0.1")
                        .setProfile(GameProfile.newBuilder()
                                .setId("00000000-0000-0000-0000-000000000001")
                                .setName("Player")
                                .addProperties(GameProfileProperty.newBuilder()
                                        .setName("minekube:bedrock_identity")
                                        .setValue("signed-envelope"))))
                .build();
    }

    private static final class TrackingApi extends SimpleConnectApi {
        private final String replacementSessionId;
        private final CountDownLatch replacementAdded = new CountDownLatch(1);

        private TrackingApi(
                VerifiedBedrockIdentityRegistry registry,
                String replacementSessionId) {
            super(mock(ConnectLogger.class), registry);
            this.replacementSessionId = replacementSessionId;
        }

        @Override
        public ConnectPlayer addPlayer(ConnectPlayer player) {
            ConnectPlayer previous = super.addPlayer(player);
            if (replacementSessionId.equals(player.getSessionId())) {
                replacementAdded.countDown();
            }
            return previous;
        }

        private boolean awaitReplacement() throws InterruptedException {
            return replacementAdded.await(2, TimeUnit.SECONDS);
        }
    }
}
