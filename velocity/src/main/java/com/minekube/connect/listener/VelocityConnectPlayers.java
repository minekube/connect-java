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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.Singleton;
import com.minekube.connect.api.player.ConnectPlayer;
import com.velocitypowered.api.proxy.InboundConnection;
import java.util.concurrent.TimeUnit;

/**
 * The Connect players of the connections currently logging in, shared by every Velocity
 * listener so that a handler running late in the login sequence can still tell whether the
 * connection was authenticated by Connect.
 */
@Singleton
public final class VelocityConnectPlayers {
    private final Cache<InboundConnection, ConnectPlayer> cache =
            CacheBuilder.newBuilder()
                    .maximumSize(500)
                    .expireAfterAccess(20, TimeUnit.SECONDS)
                    .build();

    /**
     * Remembers the Connect player of a connection Connect itself authenticated.
     */
    public void remember(InboundConnection connection, ConnectPlayer player) {
        cache.put(connection, player);
    }

    /**
     * Returns the Connect player of this connection, or {@code null} when Connect did not
     * authenticate it. Entries expire on their own; nothing removes them explicitly, because a
     * handler registered after every other plugin's must still be able to read them.
     */
    public ConnectPlayer get(InboundConnection connection) {
        return cache.getIfPresent(connection);
    }
}
