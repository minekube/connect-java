package com.minekube.connect.addon.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.network.netty.LocalSession;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Guards the one handshake branch that rewrites the client's hostname and also decides whether the
 * connector keeps its pipeline slot.
 *
 * <p>Defect reproduced end to end against connect-spigot 0.15.18 (Paper 26.3, online-mode=true,
 * endpoint {@code offlineModeHeader: false}, case t_117be469): a modded client's handshake carries a
 * {@code \0FORGE} marker, the connector strips it and then removed itself, so the fake offline login
 * that Connect injects on {@code LOGIN_START} never ran. Paper (still owning an unverified login)
 * started its own encryption handshake into the tunnel; the edge cannot answer that, so the backend
 * session ended {@code backend_connect} 21.67 ms after the tunnel opened and the player was dropped
 * into the browser hub. The same marker join completes on the same stack when the connector keeps
 * handling {@code LOGIN_START}, and it already completed on the pre-fix jar with
 * {@code online-mode=false} - which is exactly the mode where Paper never asks for encryption and
 * therefore never needs the injection.
 *
 * <p>These tests drive the production {@code handleHandshake} decision, so both the hostname the
 * server receives and the connector's pipeline decision are under test.
 */
class SpigotHandshakeStripTest {
    private static final String ENDPOINT = "motomc.play.minekube.net";
    private static final String FORGE_MARKER = "\0FORGE";
    private static final String PLAYER = "R6vibin";
    private static final UUID PLAYER_ID = UUID.fromString("5933d395-f771-4fe9-8a6f-14f34bcb7aab");

    @Test
    void keepsTheConnectorInstalledWhenItStripsAForwardingMarker() {
        Fixture fixture = new Fixture(ENDPOINT + FORGE_MARKER);

        boolean passOriginalDownstream = fixture.handler.handleHandshake(fixture.handshake, false, false);

        assertFalse(passOriginalDownstream,
                "the marked handshake must be replaced, not passed on to the server");
        assertEquals(ENDPOINT, fixture.handshake.replacedHostname,
                "the server must receive the hostname without the client's marker");
        assertFalse(fixture.handshake.stopped,
                "the connector must stay installed: its fake login cycle runs on LOGIN_START, and"
                        + " removing it here hands an online-mode server an unverified login"
                        + " (the 0.15.18 backend_connect teardown)");
    }

    @Test
    void keepsHandlingAnUnmarkedHandshakeAndPassesItThrough() {
        Fixture fixture = new Fixture(ENDPOINT);

        boolean passOriginalDownstream = fixture.handler.handleHandshake(fixture.handshake, false, false);

        assertTrue(passOriginalDownstream, "a clean handshake needs no rewrite");
        assertNull(fixture.handshake.replacedHostname, "a clean handshake must not be replaced");
        assertFalse(fixture.handshake.stopped);
    }

    @Test
    void injectsBungeeForwardingDataAndLeavesThePipeline() {
        Fixture fixture = new Fixture(PLAYER + "." + ENDPOINT + FORGE_MARKER);

        boolean passOriginalDownstream = fixture.handler.handleHandshake(fixture.handshake, true, false);

        assertFalse(passOriginalDownstream, "the rebuilt packet replaced the original");
        String forwarded = fixture.handshake.replacedHostname;
        assertTrue(forwarded.startsWith(PLAYER + "." + ENDPOINT + "\0"), forwarded);
        assertTrue(forwarded.contains(PLAYER_ID.toString().replace("-", "")), forwarded);
        assertTrue(fixture.handshake.stopped,
                "in BungeeCord forwarding mode the server owns the login, so the connector leaves");
    }

    @Test
    void staysForProtocolSupportWhenBungeeForwardingDataIsInjected() {
        Fixture fixture = new Fixture(ENDPOINT + FORGE_MARKER);

        fixture.handler.handleHandshake(fixture.handshake, true, true);

        assertFalse(fixture.handshake.stopped,
                "ProtocolSupport needs the connector to stay, or the player is kicked with"
                        + " \"unexpected hello packet\"");
    }

    @Test
    void stripForwardingDataDropsOnlyWhatFollowsTheFirstNull() {
        assertEquals(ENDPOINT, SpigotDataHandler.stripForwardingData(ENDPOINT + FORGE_MARKER));
        assertEquals(ENDPOINT, SpigotDataHandler.stripForwardingData(ENDPOINT + "\0" + "1.2.3.4\0abc\0[]"));
        assertEquals(ENDPOINT, SpigotDataHandler.stripForwardingData(ENDPOINT));
    }

    /** A handler with the session state the handshake branch reads, and a recording handshake. */
    private static final class Fixture {
        private final ConnectConfig config = mock(ConnectConfig.class);
        private final StubHandshake handshake;
        private final SpigotDataHandler handler;
        private final EmbeddedChannel channel;

        private Fixture(String hostname) {
            LocalSession.Context session = mock(LocalSession.Context.class);
            ConnectPlayer player = mock(ConnectPlayer.class);
            lenient().when(config.isDebug()).thenReturn(false);
            lenient().when(session.getPlayer()).thenReturn(player);
            lenient().when(session.getSpoofedAddress())
                    .thenReturn(InetSocketAddress.createUnresolved("93.201.72.37", 0));
            lenient().when(player.getUniqueId()).thenReturn(PLAYER_ID);
            lenient().when(player.getUsername()).thenReturn(PLAYER);
            when(player.getGameProfile()).thenReturn(new GameProfile(PLAYER, PLAYER_ID, List.of()));

            this.handshake = new StubHandshake(hostname);
            this.handler = new SpigotDataHandler(session, "packet_handler", config,
                    mock(ConnectLogger.class), null);
            this.channel = new EmbeddedChannel(handler);
        }
    }

    private static final class StubHandshake implements SpigotDataHandler.HandshakeView {
        private final String hostname;
        private String replacedHostname;
        private boolean stopped;

        private StubHandshake(String hostname) {
            this.hostname = hostname;
        }

        @Override
        public String hostname() {
            return hostname;
        }

        @Override
        public boolean replaceHostname(String hostname) {
            this.replacedHostname = hostname;
            return false; // 1.20.2+: a rebuilt packet was fired, the original is dropped
        }

        @Override
        public void stopAfterHandshake() {
            this.stopped = true;
        }
    }
}
