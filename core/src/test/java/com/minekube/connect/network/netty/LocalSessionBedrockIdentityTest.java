package com.minekube.connect.network.netty;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.bedrock.VerifiedBedrockIdentityRegistry;
import com.minekube.connect.tunnel.Tunneler;
import com.minekube.connect.watch.SessionProposal;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
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
}
