/*
 * Copyright (c) 2021-2022 Minekube. https://minekube.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author Minekube
 * @link https://github.com/minekube/connect-java
 */

package com.minekube.connect.listener;

import com.google.inject.Inject;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.config.ProxyConnectConfig;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.GameProfile.Property;
import java.util.List;

/**
 * Re-asserts Connect's own login decision after every other plugin has had its say.
 *
 * <p>Connect authenticates a tunneled player at the Minekube edge and hands the proxy an
 * already-verified, offline-mode connection ({@link VelocityListener}'s
 * {@code PostOrder.EARLY} handlers). A login plugin that unconditionally forces online mode
 * later in the same event reverts that, and the proxy then sends an encryption request that
 * nothing can answer - the player hangs at "Logging in..." forever.
 *
 * <p>This listener is a floor, not a veto. It reacts <b>only</b> to Connect's own result
 * having been changed on the event object:
 *
 * <ul>
 *   <li>It does nothing for connections Connect did not authenticate.</li>
 *   <li>It never turns another plugin's kick into a join - a disallowed result is left alone.</li>
 *   <li>It does nothing when the decision still is what Connect set.</li>
 *   <li>Operators who deliberately want another plugin to override Connect can switch it off
 *       with {@code login-reassert.enabled: false}.</li>
 * </ul>
 *
 * <p>Because the trigger is Connect's own observed state, this covers every plugin that forces
 * online mode after Connect - LibreLogin, AuthMe, nLogin, anything - without Connect reading,
 * linking against, or version-checking any of them.
 *
 * <p>By default only the profile <i>properties</i> (the skin) are restored, not the UUID:
 * login plugins commonly key their own database on the UUID the proxy ends up with, and
 * changing it out from under them breaks their lookups. Restoring the full profile is
 * available as an opt-in, with its prerequisite documented in {@code proxy-config.yml}.
 *
 * @see VelocityLateEventRegistrar for how "after every other plugin" is achieved
 */
public final class VelocityLateReassertListener {
    @Inject private VelocityConnectPlayers connectPlayers;
    @Inject private ProxyConnectConfig config;
    @Inject private ConnectLogger logger;

    public VelocityLateReassertListener() {
    }

    VelocityLateReassertListener(
            VelocityConnectPlayers connectPlayers,
            ProxyConnectConfig config,
            ConnectLogger logger) {
        this.connectPlayers = connectPlayers;
        this.config = config;
        this.logger = logger;
    }

    /**
     * Registers both handlers after every other plugin's. Called instead of the usual annotated
     * listener registration, because {@code @Subscribe} cannot express "after {@code LAST}" on
     * the Velocity API Connect compiles against.
     */
    public void register(EventManager eventManager, Object plugin) {
        VelocityLateEventRegistrar registrar = new VelocityLateEventRegistrar(eventManager, plugin);
        EventHandler<PreLoginEvent> preLoginHandler = this::onPreLoginLate;
        EventHandler<GameProfileRequestEvent> gameProfileHandler = this::onGameProfileRequestLate;
        boolean preLoginRegistered = false;
        boolean gameProfileRegistered = false;
        try {
            boolean afterLast = registrar.registerAfterLast(PreLoginEvent.class, preLoginHandler);
            preLoginRegistered = true;
            registrar.registerAfterLast(GameProfileRequestEvent.class, gameProfileHandler);
            gameProfileRegistered = true;
            logger.debug(
                    "Registered the login re-assert handlers (numeric priority: {})",
                    afterLast ? "yes" : "no, this Velocity only supports PostOrder.LAST");
        } catch (Throwable registrationFailure) {
            if (gameProfileRegistered) {
                unregisterSafely(eventManager, plugin, gameProfileHandler);
            }
            if (preLoginRegistered) {
                unregisterSafely(eventManager, plugin, preLoginHandler);
            }
            rethrow(registrationFailure);
        }
    }

    private void unregisterSafely(
            EventManager eventManager, Object plugin, EventHandler<?> handler) {
        try {
            unregister(eventManager, plugin, handler);
        } catch (Throwable rollbackFailure) {
            try {
                logger.error("Could not roll back a partially registered login re-assert handler",
                        rollbackFailure);
            } catch (Throwable ignored) {
            }
        }
    }

    private static <E> void unregister(
            EventManager eventManager, Object plugin, EventHandler<E> handler) {
        eventManager.unregister(plugin, handler);
    }

    private static void rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException) {
            throw (RuntimeException) throwable;
        }
        if (throwable instanceof Error) {
            throw (Error) throwable;
        }
        throw new RuntimeException(throwable);
    }

    void onPreLoginLate(PreLoginEvent event) {
        if (!enabled()) {
            return;
        }
        if (connectPlayers.get(event.getConnection()) == null) {
            return; // not authenticated by Connect - none of our business
        }
        PreLoginComponentResult result = event.getResult();
        if (!result.isAllowed()) {
            return; // a plugin denied this login; never convert a kick into a join
        }
        if (result.isForceOfflineMode()) {
            return; // still what Connect set
        }
        event.setResult(PreLoginComponentResult.forceOfflineMode());
        logger.debug("Re-asserted offline mode for a Connect session at pre-login; "
                + "another plugin had changed it (set login-reassert.enabled to false to allow that)");
    }

    void onGameProfileRequestLate(GameProfileRequestEvent event) {
        if (!enabled() || event.isOnlineMode()) {
            return;
        }
        ConnectPlayer player = connectPlayers.get(event.getConnection());
        if (player == null) {
            return;
        }
        GameProfile current = event.getGameProfile();
        GameProfile connectProfile = VelocityGameProfiles.fromConnectPlayer(current, player);
        GameProfile wanted = config.getLoginReassert().isRestoreFullProfile()
                ? connectProfile
                : current.withProperties(connectProfile.getProperties());
        if (sameProfile(current, wanted)) {
            return;
        }
        event.setGameProfile(wanted);
        logger.debug("Re-asserted the game profile of Connect session {}", player.getUsername());
    }

    private boolean enabled() {
        return config.getLoginReassert().isEnabled();
    }

    /**
     * {@link GameProfile} and its {@link Property} do not implement {@code equals}, so compare
     * the parts this listener can change.
     */
    private static boolean sameProfile(GameProfile left, GameProfile right) {
        if (!left.getId().equals(right.getId()) || !left.getName().equals(right.getName())) {
            return false;
        }
        List<Property> leftProperties = left.getProperties();
        List<Property> rightProperties = right.getProperties();
        if (leftProperties.size() != rightProperties.size()) {
            return false;
        }
        for (int i = 0; i < leftProperties.size(); i++) {
            Property a = leftProperties.get(i);
            Property b = rightProperties.get(i);
            if (!a.getName().equals(b.getName())
                    || !a.getValue().equals(b.getValue())
                    || !a.getSignature().equals(b.getSignature())) {
                return false;
            }
        }
        return true;
    }
}
