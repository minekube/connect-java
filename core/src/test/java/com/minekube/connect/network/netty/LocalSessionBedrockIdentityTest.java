package com.minekube.connect.network.netty;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.player.ConnectPlayerImpl;
import com.minekube.connect.tunnel.Tunneler;
import com.minekube.connect.watch.SessionProposal;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.UUID;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Authentication;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import org.junit.jupiter.api.Test;

class LocalSessionBedrockIdentityTest {
    @Test
    void discardsStagedAdmissionWhenSynchronousSetupFails() {
        ConnectPlayer player = new ConnectPlayerImpl(
                "session-1",
                new GameProfile("BedrockSteve", UUID.randomUUID(), Collections.emptyList()),
                new Auth(false),
                "");
        TrackingApi api = new TrackingApi(player);
        Session session = Session.newBuilder()
                .setId("session-1")
                .setAuth(Authentication.getDefaultInstance())
                .setPlayer(Player.newBuilder().setAddr(""))
                .build();
        LocalSession localSession = new LocalSession(
                mock(ConnectLogger.class),
                api,
                mock(Tunneler.class),
                new InetSocketAddress("127.0.0.1", 25565),
                new SessionProposal(session, reason -> {}));

        assertThrows(IllegalArgumentException.class, localSession::connect);

        assertSame(player, api.discardedPlayer);
    }

    private static final class TrackingApi extends SimpleConnectApi {
        private final ConnectPlayer player;
        private ConnectPlayer discardedPlayer;

        private TrackingApi(ConnectPlayer player) {
            super(mock(ConnectLogger.class));
            this.player = player;
        }

        @Override
        public ConnectPlayer stageAdmission(SessionProposal proposal) {
            return player;
        }

        @Override
        public void discardAdmission(ConnectPlayer player) {
            discardedPlayer = player;
        }
    }
}
