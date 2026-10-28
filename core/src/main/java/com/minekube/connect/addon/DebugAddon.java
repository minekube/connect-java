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

package com.minekube.connect.addon;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.minekube.connect.addon.debug.ChannelInDebugHandler;
import com.minekube.connect.addon.debug.ChannelOutDebugHandler;
import com.minekube.connect.addon.debug.ChannelOutObjectDebugHandler;
import com.minekube.connect.api.inject.InjectorAddon;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.util.Utils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

public final class DebugAddon implements InjectorAddon {
    /** Pipeline names the recorders are injected under; also what {@link #onRemoveInject} removes. */
    static final String OUT_HANDLER_NAME = "connect_debug_out";
    static final String IN_HANDLER_NAME = "connect_debug_in";

    @Inject private ConnectConfig config;
    @Inject private ConnectLogger logger;

    @Inject
    @Named("platformName")
    private String platformName;

    @Inject
    @Named("packetEncoder")
    private String packetEncoder;

    @Inject
    @Named("packetDecoder")
    private String packetDecoder;

    @Override
    public void onInject(Channel channel, boolean toServer) {
        logger.info("Successfully called onInject. To server? " + toServer);
        installRecorders(channel, platformName, toServer, packetEncoder, packetDecoder, logger);
    }

    /**
     * Installs the inbound and outbound packet recorders on {@code channel}.
     *
     * <p>Neither direction may fail silently: a one-sided dump looks exactly like a silent peer, and
     * has already been read as one during support work. The recorders must also survive the platform
     * renaming its pipeline handlers: Paper 26.3 names the outbound packet encoder
     * {@code outbound_config} in the injected (configuration-capable) connection and only uses
     * {@code encoder} on the player-side path, so the old "add before the configured name, else skip at
     * debug level" left every support dump outbound-silent. The encoder is therefore located in the
     * pipeline itself, and every non-obvious placement is reported at WARN.
     *
     * <p>Each direction gets its own packet budget: a shared counter let whichever direction spoke
     * first consume {@link com.minekube.connect.util.Utils#MAX_DEBUG_PACKET_COUNT} and silence the
     * other one entirely.
     */
    static void installRecorders(
            Channel channel,
            String platformName,
            boolean toServer,
            @Nullable String packetEncoder,
            @Nullable String packetDecoder,
            ConnectLogger logger) {
        ChannelPipeline pipeline = channel.pipeline();

        String outboundAnchor = outboundRecorderAnchor(pipeline, packetEncoder);
        if (outboundAnchor != null) {
            pipeline.addBefore(
                    outboundAnchor,
                    OUT_HANDLER_NAME,
                    new ChannelOutDebugHandler(platformName, toServer, new AtomicInteger(), logger));
            if (!outboundAnchor.equals(packetEncoder)) {
                logger.warn(
                        "Debug capture: packet encoder '{}' is not in this channel's pipeline ({}); "
                                + "recording the outbound direction in front of '{}' instead.",
                        packetEncoder,
                        pipeline.names(),
                        outboundAnchor);
            }
        } else {
            // Nothing in this pipeline encodes messages: record at the tail with the object recorder so
            // the direction is still visible (as pre-encoding message types), and say so.
            pipeline.addLast(
                    OUT_HANDLER_NAME,
                    new ChannelOutObjectDebugHandler(platformName, toServer, new AtomicInteger(), logger));
            logger.warn(
                    "Debug capture: no packet encoder found in this channel's pipeline ({}); "
                            + "recording the outbound direction at the pipeline tail - outbound entries "
                            + "show the pre-encoding message, not packet bytes.",
                    pipeline.names());
        }

        if (packetDecoder != null && pipeline.get(packetDecoder) != null) {
            pipeline.addBefore(
                    packetDecoder,
                    IN_HANDLER_NAME,
                    new ChannelInDebugHandler(platformName, toServer, new AtomicInteger(), logger));
        } else {
            logger.warn(
                    "Debug capture: packet decoder '{}' is not in this channel's pipeline ({}); "
                            + "the inbound direction is NOT being recorded - this dump is one-sided.",
                    packetDecoder,
                    pipeline.names());
        }
    }

    /**
     * Pipeline name to install the outbound recorder in front of, or {@code null} if this pipeline has
     * no message encoder at all.
     *
     * <p>Search order: the configured encoder name, then the packet encoder the platform actually
     * installed - recognised by class name, because Minecraft's {@code PacketEncoder} is a plain
     * {@code ChannelOutboundHandler} and not a Netty codec base class - and only then any Netty message
     * encoder, taking the handler furthest towards the tail because that is the one an outbound write
     * reaches first.
     */
    @Nullable
    static String outboundRecorderAnchor(ChannelPipeline pipeline, @Nullable String packetEncoder) {
        if (packetEncoder != null && pipeline.get(packetEncoder) != null) {
            return packetEncoder;
        }
        String firstMessageEncoder = null;
        List<String> names = pipeline.names();
        for (int i = names.size() - 1; i >= 0; i--) {
            String name = names.get(i);
            if (OUT_HANDLER_NAME.equals(name) || IN_HANDLER_NAME.equals(name)) {
                continue;
            }
            ChannelHandler handler = pipeline.get(name);
            if (handler == null) {
                continue;
            }
            if (handler.getClass().getSimpleName().contains("PacketEncoder")) {
                return name;
            }
            boolean messageEncoder =
                    handler instanceof MessageToMessageEncoder || handler instanceof MessageToByteEncoder;
            if (messageEncoder && firstMessageEncoder == null) {
                firstMessageEncoder = name;
            }
        }
        return firstMessageEncoder;
    }

    @Override
    public void onRemoveInject(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        Utils.removeHandler(pipeline, OUT_HANDLER_NAME);
        Utils.removeHandler(pipeline, IN_HANDLER_NAME);
    }

    @Override
    public boolean shouldInject() {
        return config.isDebug();
    }
}
