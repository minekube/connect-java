package com.minekube.connect.listener;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.config.ProxyConnectConfig;
import com.minekube.connect.config.ProxyConnectConfig.LoginReassertConfig;
import com.minekube.connect.player.ConnectPlayerImpl;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.GameProfile.Property;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/**
 * The decision logic of Connect's defensive login re-assert.
 *
 * <p>Every case here is one of the fail-safe paths the design depends on: the re-assert only
 * ever acts on Connect's own sessions, never overrides another plugin's kick, never fires when
 * nothing changed the decision, and is fully switched off by
 * {@code login-reassert.enabled: false}.
 *
 * <p>Ordering — that these handlers actually run after every other plugin's — is proven against
 * the real Velocity dispatcher in {@code VelocityLateEventOrderTest}
 * ({@code velocity/src/eventOrderTest}).
 */
class VelocityLateReassertListenerTest {
    private static final UUID CONNECT_UUID = UUID.fromString("f912bf90-8349-565f-9dc0-9891923c0cc3");
    private static final UUID PROXY_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void preLoginReassertsOfflineModeWhenAnotherPluginForcedOnlineMode() throws Exception {
        Fixture fixture = Fixture.connectSession(true, false);
        PreLoginEvent event = fixture.preLogin(PreLoginComponentResult.forceOnlineMode());

        fixture.listener.onPreLoginLate(event);

        assertTrue(event.getResult().isForceOfflineMode(),
                "Connect must restore its own pre-login decision after other plugins ran");
        assertTrue(event.getResult().isAllowed());
    }

    @Test
    void preLoginNeverOverridesAnotherPluginsKick() throws Exception {
        Fixture fixture = Fixture.connectSession(true, false);
        PreLoginComponentResult denied =
                PreLoginComponentResult.denied(Component.text("banned"));
        PreLoginEvent event = fixture.preLogin(denied);

        fixture.listener.onPreLoginLate(event);

        assertSame(denied, event.getResult(), "a deny must survive the re-assert untouched");
        assertFalse(event.getResult().isAllowed());
    }

    @Test
    void preLoginIsANoOpWhenNobodyChangedConnectsDecision() throws Exception {
        Fixture fixture = Fixture.connectSession(true, false);
        PreLoginComponentResult offline = PreLoginComponentResult.forceOfflineMode();
        PreLoginEvent event = fixture.preLogin(offline);

        fixture.listener.onPreLoginLate(event);

        assertSame(offline, event.getResult(), "nothing to re-assert, so nothing may be written");
    }

    @Test
    void preLoginIgnoresConnectionsConnectDidNotAuthenticate() throws Exception {
        Fixture fixture = Fixture.foreignSession(true, false);
        PreLoginComponentResult online = PreLoginComponentResult.forceOnlineMode();
        PreLoginEvent event = fixture.preLogin(online);

        fixture.listener.onPreLoginLate(event);

        assertSame(online, event.getResult(),
                "a connection Connect did not authenticate is none of Connect's business");
    }

    @Test
    void preLoginDoesNothingWhenTheOffSwitchIsDisabled() throws Exception {
        Fixture fixture = Fixture.connectSession(false, false);
        PreLoginComponentResult online = PreLoginComponentResult.forceOnlineMode();
        PreLoginEvent event = fixture.preLogin(online);

        fixture.listener.onPreLoginLate(event);

        assertSame(online, event.getResult(),
                "login-reassert.enabled: false must restore the pre-fix behaviour exactly");
    }

    @Test
    void gameProfileRestoresOnlyThePropertiesByDefault() throws Exception {
        Fixture fixture = Fixture.connectSession(true, false);
        GameProfileRequestEvent event = fixture.gameProfileRequest();

        fixture.listener.onGameProfileRequestLate(event);

        assertEquals(PROXY_UUID, event.getGameProfile().getId(),
                "the default must not take the UUID away from a login plugin that keys on it");
        assertEquals("RewrittenByAnotherPlugin", event.getGameProfile().getName());
        assertEquals(Collections.singletonList("textures"), propertyNames(event.getGameProfile()));
    }

    @Test
    void gameProfileRestoresTheFullProfileWhenOptedIn() throws Exception {
        Fixture fixture = Fixture.connectSession(true, true);
        GameProfileRequestEvent event = fixture.gameProfileRequest();

        fixture.listener.onGameProfileRequestLate(event);

        assertEquals(CONNECT_UUID, event.getGameProfile().getId());
        assertEquals("ConnectSteve", event.getGameProfile().getName());
        assertEquals(Collections.singletonList("textures"), propertyNames(event.getGameProfile()));
    }

    @Test
    void gameProfileDoesNothingWhenTheOffSwitchIsDisabled() throws Exception {
        Fixture fixture = Fixture.connectSession(false, true);
        GameProfileRequestEvent event = fixture.gameProfileRequest();
        GameProfile before = event.getGameProfile();

        fixture.listener.onGameProfileRequestLate(event);

        assertSame(before, event.getGameProfile());
    }

    /**
     * The normal case with no conflicting plugin installed: the profile is already the one
     * Connect's own EARLY handler produced, so the re-assert must write nothing at all - and in
     * particular must not append a second copy of Connect's properties.
     */
    @Test
    void gameProfileIsANoOpWhenNobodyChangedIt() throws Exception {
        Fixture fixture = Fixture.connectSession(true, true);
        GameProfileRequestEvent event = fixture.gameProfileRequest();
        // What VelocityListener#onGameProfileRequest already did at PostOrder.EARLY.
        event.setGameProfile(
                VelocityGameProfiles.fromConnectPlayer(event.getGameProfile(), connectPlayer()));
        GameProfile afterConnectsEarlyHandler = event.getGameProfile();

        fixture.listener.onGameProfileRequestLate(event);

        assertSame(afterConnectsEarlyHandler, event.getGameProfile(),
                "nothing changed the profile, so the re-assert must not rewrite it");
        assertEquals(Collections.singletonList("textures"),
                propertyNames(event.getGameProfile()));
    }

    @Test
    void gameProfileIgnoresConnectionsConnectDidNotAuthenticate() throws Exception {
        Fixture fixture = Fixture.foreignSession(true, true);
        GameProfileRequestEvent event = fixture.gameProfileRequest();
        GameProfile before = event.getGameProfile();

        fixture.listener.onGameProfileRequestLate(event);

        assertSame(before, event.getGameProfile());
    }

    @Test
    void partialRegistrationRollsBackOnlyTheHandlerThatWasRegistered() {
        PartialFailureEventManager eventManager = new PartialFailureEventManager();
        ConnectLogger logger = mock(ConnectLogger.class);
        VelocityListenerRegistration registration =
                new VelocityListenerRegistration(eventManager, null, logger);

        assertDoesNotThrow(() -> registration.register(new VelocityLateReassertListener()));

        assertEquals(1, eventManager.registeredHandlers.size());
        assertEquals(1, eventManager.unregisteredHandlers.size());
        assertSame(eventManager.registeredHandlers.get(0), eventManager.unregisteredHandlers.get(0));
        assertFalse(eventManager.unregisterListenersCalled);
    }

    private static List<String> propertyNames(GameProfile profile) {
        return profile.getProperties().stream()
                .map(Property::getName)
                .collect(Collectors.toList());
    }

    private static final class Fixture {
        final VelocityLateReassertListener listener;
        final InboundConnection connection = mock(InboundConnection.class);

        private Fixture(boolean enabled, boolean restoreFullProfile, boolean connectSession)
                throws Exception {
            VelocityConnectPlayers players = new VelocityConnectPlayers();
            if (connectSession) {
                players.remember(connection, connectPlayer());
            }
            listener = new VelocityLateReassertListener(
                    players, config(enabled, restoreFullProfile), mock(ConnectLogger.class));
        }

        static Fixture connectSession(boolean enabled, boolean restoreFullProfile) throws Exception {
            return new Fixture(enabled, restoreFullProfile, true);
        }

        static Fixture foreignSession(boolean enabled, boolean restoreFullProfile) throws Exception {
            return new Fixture(enabled, restoreFullProfile, false);
        }

        PreLoginEvent preLogin(PreLoginComponentResult result) {
            PreLoginEvent event = new PreLoginEvent(connection, "ConnectSteve");
            event.setResult(result);
            return event;
        }

        /** The profile as a login plugin left it: its own UUID and name, and no skin. */
        GameProfileRequestEvent gameProfileRequest() {
            GameProfile rewritten =
                    new GameProfile(PROXY_UUID, "RewrittenByAnotherPlugin", Collections.emptyList());
            return new GameProfileRequestEvent(connection, rewritten, false);
        }
    }

    private static ConnectPlayer connectPlayer() {
        return new ConnectPlayerImpl(
                "session-1",
                new com.minekube.connect.api.player.GameProfile(
                        "ConnectSteve",
                        CONNECT_UUID,
                        Collections.singletonList(
                                new com.minekube.connect.api.player.GameProfile.Property(
                                        "textures", "skin-value", "skin-signature"))),
                new Auth(false),
                "");
    }

    /**
     * The config classes are populated reflectively by the config loader and expose no setters,
     * so build the wanted state the same way.
     */
    private static ProxyConnectConfig config(boolean enabled, boolean restoreFullProfile)
            throws Exception {
        ProxyConnectConfig config = new ProxyConnectConfig();
        LoginReassertConfig reassert = config.getLoginReassert();
        set(reassert, "enabled", enabled);
        set(reassert, "restoreFullProfile", restoreFullProfile);
        return config;
    }

    private static void set(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static final class PartialFailureEventManager implements EventManager {
        final List<EventHandler<?>> registeredHandlers = new ArrayList<>();
        final List<EventHandler<?>> unregisteredHandlers = new ArrayList<>();
        boolean unregisterListenersCalled;

        @Override
        public void register(Object plugin, Object listener) {
            throw new AssertionError("late handlers must use explicit registration");
        }

        @Override
        public <E> void register(Object plugin, Class<E> eventClass, PostOrder postOrder,
                EventHandler<E> handler) {
            if (!registeredHandlers.isEmpty()) {
                throw new AssertionError("second late-handler registration failed");
            }
            registeredHandlers.add(handler);
        }

        @Override
        public <E> CompletableFuture<E> fire(E event) {
            return CompletableFuture.completedFuture(event);
        }

        @Override
        public void unregisterListeners(Object plugin) {
            unregisterListenersCalled = true;
        }

        @Override
        public void unregisterListener(Object plugin, Object listener) {
        }

        @Override
        public <E> void unregister(Object plugin, EventHandler<E> handler) {
            unregisteredHandlers.add(handler);
        }
    }
}
