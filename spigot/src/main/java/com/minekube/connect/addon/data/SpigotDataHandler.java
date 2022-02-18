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

package com.minekube.connect.addon.data;

import static com.minekube.connect.util.ReflectionUtils.getCastedValue;
import static com.minekube.connect.util.ReflectionUtils.setValue;

import com.google.gson.Gson;
import com.minekube.connect.config.FloodgateConfig;
import com.minekube.connect.network.netty.LocalSession.Context;
import com.minekube.connect.player.FloodgateHandshakeHandler;
import com.minekube.connect.player.FloodgateHandshakeHandler.HandshakeResult;
import com.minekube.connect.util.ClassNames;
import com.minekube.connect.util.ProxyUtils;
import io.netty.util.AttributeKey;
import java.net.InetSocketAddress;
import java.util.function.UnaryOperator;

public final class SpigotDataHandler extends CommonDataHandler {
    private final Context sessionCtx;
    private final String packetHandlerName;

    private Object networkManager;
    private final boolean proxyData;

    public SpigotDataHandler(
            Context sessionCtx,
            String packetHandlerName,
            FloodgateHandshakeHandler handshakeHandler,
            FloodgateConfig config,
            AttributeKey<String> kickMessageAttribute) {
        super(handshakeHandler, config, kickMessageAttribute, new PacketBlocker());
        this.sessionCtx = sessionCtx;
        this.packetHandlerName = packetHandlerName;
        this.proxyData = ProxyUtils.isProxyData();
    }

//    @Override
//    protected void setNewIp(Channel channel, InetSocketAddress newIp) {
//        setValue(networkManager, ClassNames.SOCKET_ADDRESS, newIp);
//    }

    @Override
    protected Object setHostname(Object handshakePacket, String hostname) {
        setValue(handshakePacket, ClassNames.HANDSHAKE_HOST, hostname);
        return handshakePacket;
    }

    @Override
    protected boolean shouldRemoveHandler(HandshakeResult result) {
        if (getKickMessage() != null) {
            // we also have to keep this handler if we want to kick then with a disconnect message
            return false;
        }

        // The server will do all the work if BungeeCord/Velocity mode is enabled
        if (!proxyData) {
            // The server is likely in non-proxy online mode, and we have to help it.
            // Use a spoofedUUID for initUUID (just like Bungeecord)
            setValue(networkManager, "spoofedUUID", sessionCtx.getPlayer().getUniqueId());
        }

        // we can only remove the handler if the server has proxy player data forwarding enabled.
        // Otherwise, we need to wait and disable it.
        return proxyData; // && ClassNames.PAPER_DISABLE_USERNAME_VALIDATION == null;
    }

    @Override
    protected boolean shouldCallFireRead(Object queuedPacket) {
        // we have to ignore the 'login start' packet,
        // otherwise the server will ask the user to login
        try {
            if (checkAndHandleLogin(queuedPacket)) {
                return false;
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        return true;
    }

    // TODO catch server -> client: "velocity:player_info" login plugin message packet
    // take createVelocityForwardingData code from velocity
    // and send player data response 

    @Override
    public boolean channelRead(Object packet) throws Exception {
        if (ClassNames.HANDSHAKE_PACKET.isInstance(packet)) {
            if (ProxyUtils.isBungeeData()) {
                String hostname = getCastedValue(packet, ClassNames.HANDSHAKE_HOST);
                setHostname(packet, createLegacyForwardingAddress(hostname));
                removeSelf();
                return true;
            }

            return true;

//            // ProtocolSupport would break if we added this during the creation of this handler
//            ctx.pipeline().addAfter("splitter", "floodgate_packet_blocker", blocker);
//
//            networkManager = ctx.channel().pipeline().get(packetHandlerName);
//
//            // Server has bungee enabled and expects to receive player data in hostname of handshake packet
//            // Let's modify the hostname and pass it on.
//
//            handle(packet, getCastedValue(packet, ClassNames.HANDSHAKE_HOST));
//            // otherwise, it'll get read twice. once by the packet queue and once by this method
//            return false;
        }

        return !checkAndHandleLogin(packet);
    }

    private boolean checkAndHandleLogin(Object packet) throws Exception {
        if (ProxyUtils.isProxyData()) {
            return true; // work done by server
        }
        if (ClassNames.LOGIN_START_PACKET.isInstance(packet)) {
            System.out.println("GOT LOGIN START");
            Object packetListener = ClassNames.PACKET_LISTENER.get(networkManager);

            String kickMessage = getKickMessage();
            if (kickMessage != null) {
                disconnect(packetListener, kickMessage);
                return true;
            }

            // check if the server is actually in the Login state
            if (!ClassNames.LOGIN_LISTENER.isInstance(packetListener)) {
                // player is not in the login state, abort

                // I would've liked to close the channel for security reasons, but our big friend
                // ProtocolSupport, who likes to break things, doesn't work otherwise
                ctx.pipeline().remove(this);
                return true;
            }

//            if (ClassNames.PAPER_DISABLE_USERNAME_VALIDATION != null) {
//                // ensure that Paper will not be checking
//                setValue(packetListener, ClassNames.PAPER_DISABLE_USERNAME_VALIDATION, true);
//                if (proxyData) {
//                    // the server will handle the rest if we have proxy data
//                    ctx.pipeline().remove(this);
//                    return false;
//                }
//            }

            // set the player his GameProfile, we can't change the username without this
//            GameProfile gameProfile = new GameProfile(
//                    player.getUniqueId(), player.getUsername()
//            );
//            // TODO robin: try if we can reflectively set PropertyMap of gameProfile here already
//            setValue(packetListener, ClassNames.LOGIN_PROFILE, gameProfile);

            // we have to fake the offline player (login) cycle
            // just like on Spigot:

            // LoginListener#initUUID
            // new LoginHandler().fireEvents();

            // and the tick of LoginListener will do the rest

            ClassNames.INIT_UUID.invoke(packetListener);

            Object loginHandler =
                    ClassNames.LOGIN_HANDLER_CONSTRUCTOR.newInstance(packetListener);
            ClassNames.FIRE_LOGIN_EVENTS.invoke(loginHandler);

            ctx.pipeline().remove(this);
            return true;
        }
        return false;
    }

    private void disconnect(Object packetListener, String kickMessage) throws Exception {
        // both versions close the channel for us
        if (ClassNames.LOGIN_LISTENER.isInstance(packetListener)) {
            ClassNames.LOGIN_DISCONNECT.invoke(packetListener, kickMessage);
        } else {
            // ProtocolSupport for example has their own PacketLoginInListener implementation
            ClassNames.NETWORK_EXCEPTION_CAUGHT.invoke(
                    networkManager,
                    ctx, new IllegalStateException(kickMessage)
            );
        }
    }

    private static final Gson GSON = new Gson();

    // take from https://github.com/PaperMC/Velocity/blob/2586210ca67f2510eb4f91bf7567643f8a26ee7b/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/VelocityServerConnection.java#L126
    private String createLegacyForwardingAddress(String virtualHost) {
        // BungeeCord IP forwarding is simply a special injection after the "address" in the handshake,
        // separated by \0 (the null byte). In order, you send the original host, the player's IP, their
        // UUID (undashed), and if you are in online-mode, their login properties (from Mojang).
        StringBuilder data = new StringBuilder()
                .append(virtualHost)
                .append('\0')
                .append(getPlayerRemoteAddressAsString())
                .append('\0')
                .append(sessionCtx.getPlayer().getUniqueId().toString().replaceAll("-", ""))
                .append('\0');
        GSON.toJson(UnaryOperator.identity().apply(sessionCtx.getPlayer().getProperties()), data);
        return data.toString();
    }

    private String getPlayerRemoteAddressAsString() {
        final String addr = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
        int ipv6ScopeIdx = addr.indexOf('%');
        if (ipv6ScopeIdx == -1) {
            return addr;
        } else {
            return addr.substring(0, ipv6ScopeIdx);
        }
    }
}
