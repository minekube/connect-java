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

package com.minekube.connect.api;

import com.minekube.connect.api.player.ConnectPlayer;
import io.netty.util.AttributeKey;

/**
 * Netty channel attributes Connect publishes for third-party plugins.
 *
 * <p><b>Stable public contract.</b> The attribute <i>names</i> in this class are a permanent part
 * of Connect's public integration surface. External plugins (login/auth plugins in particular)
 * identify Connect-tunneled connections by these names, usually without compiling against Connect
 * at all. They will therefore never be removed or renamed. Treat them exactly like a published
 * method signature: additive changes only.
 */
public final class ConnectAttributes {
    /**
     * Marks a proxy-facing connection as <b>already authenticated by Connect at the edge</b>.
     *
     * <p>The wire-visible attribute name is exactly {@code "connect-player"}, deliberately
     * mirroring Floodgate's {@code "floodgate-player"} convention so plugin authors who already
     * exempt Floodgate players can apply the identical pattern.
     *
     * <p><b>When it is set.</b> Connect sets this attribute when it creates the proxy-facing
     * channel, before any platform login event fires, so it is readable from Velocity's
     * {@code PreLoginEvent}/{@code GameProfileRequestEvent}, BungeeCord's {@code PreLoginEvent},
     * and Spigot's login handling alike. It is set <b>only</b> for sessions Connect itself
     * authenticated, i.e. when {@link com.minekube.connect.api.player.Auth#isPassthrough()} is
     * {@code false}. Passthrough sessions are not authenticated by Connect and must still go
     * through the server's own login flow, so the attribute is absent for them.
     *
     * <p><b>How to integrate.</b> A plugin that forces online mode, rewrites the game profile, or
     * gates the player behind its own login flow should skip all of that when this attribute is
     * present:
     *
     * <pre>{@code
     * private static final AttributeKey<Object> CONNECT_PLAYER =
     *         AttributeKey.valueOf("connect-player");
     *
     * if (channel.hasAttr(CONNECT_PLAYER) && channel.attr(CONNECT_PLAYER).get() != null) {
     *     return; // externally authenticated by Connect - do not force online mode
     * }
     * }</pre>
     *
     * <p>Netty keys are interned by name, so the snippet above works without any compile-time
     * dependency on Connect: presence alone is the signal. Plugins that do depend on Connect's API
     * jar can use this constant directly and read the {@link ConnectPlayer} value for the player's
     * real Mojang UUID, username and game profile. That is why the value is a {@code ConnectPlayer}
     * rather than a bare {@code UUID} - it carries everything an integrator has been observed to
     * need (identity, skin properties, {@link com.minekube.connect.api.player.Auth}), while callers
     * who only need the boolean "is this Connect?" answer pay nothing for it.
     *
     * <p>Once the player is online, {@link ConnectApi#isConnectPlayer(java.util.UUID)} and
     * {@link ConnectApi#getPlayer(java.util.UUID)} provide the same information keyed by UUID.
     * This attribute exists because those lookups are only available after login completes, which
     * is too late to avoid conflicting with Connect during login.
     */
    public static final AttributeKey<ConnectPlayer> CONNECT_PLAYER =
            AttributeKey.valueOf("connect-player");

    private ConnectAttributes() {
    }
}
