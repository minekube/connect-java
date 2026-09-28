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
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * @author Minekube
 * @link https://github.com/minekube/connect-java
 */

package com.minekube.connect.addon.data;

import com.minekube.connect.api.logger.ConnectLogger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Attributes a tunneled (authenticated) login that never completes, on the connector side.
 *
 * <p>A packet-level login plugin (PacketEvents-style) sits <em>upstream</em> of this pipeline
 * position and clears the frame buffer when it cancels a packet, so {@code LOGIN_START} can vanish
 * before the connector's own data handler sees it. That handler's fake offline-login cycle is
 * driven from a single site inside its {@code LOGIN_START} branch, so it then never runs: the
 * client receives nothing and the only line anywhere is the server's own cause-blind
 * {@code Took too long to log in}. This watchdog sees the same packets the connector's login path
 * sees and, when a handshake was observed but no {@code LOGIN_START} follows within
 * {@link #LOGIN_START_WINDOW_MILLIS}, emits exactly one default-verbosity line naming the session
 * and the probable cause.
 *
 * <p>Log-first by design: this handler never consumes, replaces, delays or fabricates a packet and
 * never closes a channel, so a healthy session is unaffected. A kick that names the cause would
 * change user-visible behaviour and is deliberately not part of this change.
 *
 * <p>Packets are identified by class name - the convention introduced by
 * {@link SpigotChatSessionPacketFilter} - instead of the server-internals reflection in
 * {@link com.minekube.connect.util.ClassNames}, which keeps this handler independent of the running
 * server version and unit-testable with an {@code EmbeddedChannel}. The names mirror the candidates
 * {@code ClassNames} resolves for its {@code HANDSHAKE_PACKET} and {@code LOGIN_START_PACKET}; keep
 * both in sync when a Minecraft version moves them again.
 *
 * <p>Only non-passthrough sessions reach this handler: for passthrough (offline-mode) endpoints
 * Connect deliberately injects no addons at all, and it owns no login decision to attribute there.
 */
final class SpigotLoginStallWatchdog extends ChannelInboundHandlerAdapter {
    static final String HANDLER_NAME = "connect_login_stall_watchdog";

    /**
     * Bounded window after the handshake in which {@code LOGIN_START} is expected. Chosen to land
     * well before the server's own login timeout (~30 s / 600 ticks) so the cause-naming line is the
     * first one the operator reads, and far above the milliseconds a healthy handshake-to-login-start
     * gap takes.
     */
    static final long LOGIN_START_WINDOW_MILLIS = Duration.ofSeconds(10).toMillis();

    // Mojang-mapped names on 1.20.5+, obfuscated fallbacks before that (see ClassNames).
    private static final String HANDSHAKE_PACKET =
            "net.minecraft.network.protocol.handshake.ClientIntentionPacket";
    private static final String LEGACY_HANDSHAKE_PACKET = "PacketHandshakingInSetProtocol";
    private static final String LOGIN_START_PACKET =
            "net.minecraft.network.protocol.login.ServerboundHelloPacket";
    private static final String LEGACY_LOGIN_START_PACKET = "PacketLoginInStart";

    private final ConnectLogger logger;
    private final String playerName;
    private final String sessionId;
    private final String endpointId;

    private ScheduledFuture<?> deadline;
    private boolean handshakeSeen;
    private boolean loginStartSeen;
    private boolean reported;
    private int packetsSeen;

    SpigotLoginStallWatchdog(
            ConnectLogger logger,
            String playerName,
            String sessionId,
            String endpointId) {
        this.logger = logger;
        this.playerName = playerName;
        this.sessionId = sessionId;
        this.endpointId = endpointId;
    }

    /**
     * Installs the watchdog at its production position: immediately <em>upstream</em> of the
     * connector's {@code connect_data_handler}.
     *
     * <p>The position is the point of this handler. {@link SpigotDataHandler} consumes
     * {@code LOGIN_START} (it does not forward it), so a watchdog installed downstream of it would
     * miss every healthy login and report a stall for each session. Anchoring on the data handler's
     * name keeps the order correct regardless of the order of the surrounding pipeline edits.
     */
    static void install(ChannelPipeline pipeline, SpigotLoginStallWatchdog watchdog) {
        pipeline.addBefore(SpigotDataHandler.HANDLER_NAME, HANDLER_NAME, watchdog);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        packetsSeen++;
        if (isPacket(msg, HANDSHAKE_PACKET, LEGACY_HANDSHAKE_PACKET)) {
            armDeadline(ctx);
        } else if (isPacket(msg, LOGIN_START_PACKET, LEGACY_LOGIN_START_PACKET)) {
            loginStartSeen = true;
            cancelDeadline();
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelDeadline();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cancelDeadline();
        super.handlerRemoved(ctx);
    }

    private void armDeadline(ChannelHandlerContext ctx) {
        handshakeSeen = true;
        if (deadline != null) {
            return; // one window per session
        }
        deadline = ctx.executor().schedule(
                this::reportMissingLoginStart, LOGIN_START_WINDOW_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void cancelDeadline() {
        if (deadline != null) {
            deadline.cancel(false);
            deadline = null;
        }
    }

    /**
     * Runs on the session's event loop once the window elapsed without a {@code LOGIN_START}.
     */
    private void reportMissingLoginStart() {
        if (!handshakeSeen || loginStartSeen || reported) {
            return;
        }
        reported = true;
        deadline = null;
        logger.warn(
                "Connect tunneled login stalled: no LOGIN_START reached the connector within {} ms"
                        + " player={} session={} endpoint={} packets={} - probable cause: a"
                        + " packet-level login plugin in the pipeline consumed the login start"
                        + " before connect_data_handler, so Connect's offline-login cycle never ran"
                        + " and the client is left without a login response; note: connect-player"
                        + " never fires for passthrough sessions, so a connect-player exemption is"
                        + " not a fix on an offline-mode endpoint",
                LOGIN_START_WINDOW_MILLIS, playerName, sessionId, endpointId, packetsSeen);
    }

    private static boolean isPacket(Object msg, String modernName, String legacyName) {
        if (msg == null) {
            return false;
        }
        String className = msg.getClass().getName();
        return className.equals(modernName) || className.equals(legacyName)
                || className.endsWith("." + legacyName);
    }
}
