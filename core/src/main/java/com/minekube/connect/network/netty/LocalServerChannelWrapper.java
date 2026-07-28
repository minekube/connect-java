/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
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
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Floodgate
 */

package com.minekube.connect.network.netty;

import com.minekube.connect.api.ConnectAttributes;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.network.netty.LocalSession.Context;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;

/**
 * If the incoming channel is an instance of LocalChannelWithRemoteAddress, this server creates a
 * LocalChannelWrapper for the other end and attaches the spoofed remote address
 */
public class LocalServerChannelWrapper extends LocalServerChannel {
    @Override
    protected LocalChannel newLocalChannel(LocalChannel peer) {
        // LocalChannel here should be an instance of LocalChannelWithSessionContext,
        // which we can use to set the "remote address" on the other end
        // and access related session data from the channel
        if (peer instanceof LocalChannelWithSessionContext) {
            LocalChannelWrapper channel = new LocalChannelWrapper(this, peer);
            Context context = ((LocalChannelWithSessionContext) peer).getContext();
            channel.wrapper().setContext(context);
            markExternallyAuthenticated(channel, context);
            return channel;
        }
        return super.newLocalChannel(peer);
    }

    /**
     * Publishes the {@link ConnectAttributes#CONNECT_PLAYER} marker on the proxy-facing channel.
     *
     * <p>This is the earliest point at which both the channel and the Connect player identity
     * exist, so the marker is readable by every platform's login handling (Velocity, BungeeCord,
     * Spigot) before any of their events fire. Passthrough sessions are not authenticated by
     * Connect and are deliberately left unmarked so they still go through the server's own login
     * flow. See {@link ConnectAttributes#CONNECT_PLAYER} for the public contract.
     */
    private static void markExternallyAuthenticated(LocalChannelWrapper channel, Context context) {
        if (context == null) {
            return;
        }
        ConnectPlayer player = context.getPlayer();
        if (player == null || player.getAuth() == null || player.getAuth().isPassthrough()) {
            return;
        }
        channel.attr(ConnectAttributes.CONNECT_PLAYER).set(player);
    }
}
