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
import com.minekube.connect.network.netty.LocalSession;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

/**
 * The BungeeCord counterpart of {@code VelocityLateReassertListener}: re-asserts Connect's own
 * pre-login decision after every other plugin has had its say, so that a login plugin forcing
 * online mode on a Connect-tunneled connection cannot leave the player hanging at
 * "Logging in..." forever.
 *
 * <p>{@link EventHandler#priority()} is a plain {@code byte} and BungeeCord's event bus
 * dispatches the whole byte range, so {@link Byte#MAX_VALUE} runs strictly after
 * {@code EventPriority.HIGHEST} (64). That is the only correct lever here: BungeeCord breaks
 * ties between equal priorities with a {@code HashMap} keyed by listener identity, so
 * registration order - and therefore any {@code softDepends} - decides nothing.
 *
 * <p>Same guarantees as on Velocity: only connections Connect itself authenticated are touched,
 * another plugin's kick is never overridden, and {@code login-reassert.enabled: false} turns
 * the whole thing off.
 *
 * <p>BungeeCord's {@code PendingConnection} exposes no profile-properties API at pre-login, so
 * the profile half of this is limited to the UUID and username, behind the same opt-in
 * {@code login-reassert.restore-full-profile}.
 */
public final class BungeeLateReassertListener implements Listener {
    @Inject private ProxyConnectConfig config;
    @Inject private ConnectLogger logger;

    @EventHandler(priority = Byte.MAX_VALUE)
    public void onPreLoginLate(PreLoginEvent event) {
        if (!config.getLoginReassert().isEnabled()) {
            return;
        }
        if (event.isCancelled()) {
            return; // a plugin denied this login; never convert a kick into a join
        }
        try {
            PendingConnection connection = event.getConnection();
            LocalSession.context(BungeeConnections.channel(connection),
                    ctx -> reassert(connection, ctx.getPlayer()));
        } catch (Exception exception) {
            // Never let the defensive floor itself break a login: without it the connection is
            // exactly where it would have been before this listener existed.
            logger.error("Failed to re-assert Connect's pre-login decision", exception);
        }
    }

    private void reassert(PendingConnection connection, ConnectPlayer player) {
        if (player.getAuth().isPassthrough()) {
            return; // not authenticated by Connect - none of our business
        }
        if (connection.isOnlineMode()) {
            connection.setOnlineMode(false);
            logger.debug("Re-asserted offline mode for Connect session {} at pre-login; another "
                            + "plugin had changed it (set login-reassert.enabled to false to allow that)",
                    player.getUsername());
        }
        if (!config.getLoginReassert().isRestoreFullProfile()) {
            return;
        }
        if (!player.getUniqueId().equals(connection.getUniqueId())) {
            connection.setUniqueId(player.getUniqueId());
        }
        if (!player.getUsername().equals(connection.getName())) {
            BungeeConnections.setName(connection, player.getUsername());
        }
    }
}
