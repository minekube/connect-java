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

package com.minekube.connect.network.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minekube.connect.api.ConnectAttributes;
import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.network.netty.LocalSession.Context;
import com.minekube.connect.watch.SessionProposal;
import io.netty.channel.local.LocalChannel;
import io.netty.util.AttributeKey;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionProtocol;
import org.junit.jupiter.api.Test;

/**
 * Boundary test for the public {@code connect-player} channel attribute.
 *
 * <p>The attribute name and the place it is set are a permanent public contract that third-party
 * login plugins depend on (see {@link ConnectAttributes#CONNECT_PLAYER}). Renaming the attribute,
 * or moving the marker off the proxy-facing channel creation path, silently breaks every external
 * integration - so both are pinned here.
 */
class ConnectPlayerAttributeBoundaryTest {

    /**
     * The literal a third-party plugin writes. Deliberately duplicated instead of referencing the
     * constant: a rename of the constant's value must fail this test.
     */
    private static final String WIRE_NAME = "connect-player";

    @Test
    void attributeNameIsExactlyConnectPlayer() {
        assertEquals(WIRE_NAME, ConnectAttributes.CONNECT_PLAYER.name(),
                "connect-player is a permanent public contract and must not be renamed");
    }

    @Test
    void markerIsSetOnProxyFacingChannelForAuthenticatedSession() {
        ConnectPlayer player = connectPlayer(false);
        LocalChannel channel = newProxyFacingChannel(player);

        // Read it back exactly the way an external plugin would: by name only, with no
        // compile-time dependency on Connect.
        AttributeKey<Object> externalKey = AttributeKey.valueOf(WIRE_NAME);
        assertNotNull(channel.attr(externalKey).get(),
                "externally authenticated Connect sessions must be marked at channel creation");
        assertSame(player, channel.attr(ConnectAttributes.CONNECT_PLAYER).get());
    }

    @Test
    void markerIsAbsentForPassthroughSession() {
        LocalChannel channel = newProxyFacingChannel(connectPlayer(true));

        assertNull(channel.attr(AttributeKey.valueOf(WIRE_NAME)).get(),
                "passthrough sessions are not authenticated by Connect and must still go "
                        + "through the server's own login flow");
    }

    /**
     * Drives the real production path: {@link LocalServerChannelWrapper#newLocalChannel} is what
     * creates the channel the proxy's login handling sees.
     */
    private static LocalChannel newProxyFacingChannel(ConnectPlayer player) {
        LocalChannelWithSessionContext peer = new LocalChannelWithSessionContext();
        peer.setContext(context(player));
        return new LocalServerChannelWrapper().newLocalChannel(peer);
    }

    private static ConnectPlayer connectPlayer(boolean passthrough) {
        ConnectPlayer player = mock(ConnectPlayer.class);
        when(player.getAuth()).thenReturn(new Auth(passthrough));
        return player;
    }

    private static Context context(ConnectPlayer player) {
        try {
            Constructor<Context> constructor = Context.class.getDeclaredConstructor(
                    ConnectPlayer.class,
                    InetSocketAddress.class,
                    SessionProposal.class,
                    String.class,
                    String.class,
                    SessionProtocol.class,
                    com.minekube.connect.bedrock.BedrockAdmissionCoordinator.AdmissionToken.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    player,
                    new InetSocketAddress("127.0.0.1", 0),
                    mock(SessionProposal.class),
                    "endpoint",
                    "org",
                    SessionProtocol.SESSION_PROTOCOL_JAVA,
                    null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "LocalSession.Context shape changed; update the connect-player boundary test",
                    e);
        }
    }
}
