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

package com.minekube.connect.addon.debug;

import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.util.Utils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Outbound recorder used when the channel's pipeline has no encoder to sit in front of.
 *
 * <p>{@link ChannelOutDebugHandler} can only record {@link ByteBuf} messages, so at the pipeline tail -
 * where a write is still the platform's own packet object - it would silently record nothing and make a
 * live backend look silent. This recorder logs whatever the platform is writing (type plus a hex dump
 * when it is already bytes) and forwards it unchanged, so the direction is visible even from a degraded
 * position.
 */
@Sharable
public final class ChannelOutObjectDebugHandler extends ChannelOutboundHandlerAdapter {
    private final String direction;
    private final ConnectLogger logger;
    private final AtomicInteger packetCount;

    public ChannelOutObjectDebugHandler(
            String implementationType,
            boolean toServer,
            AtomicInteger packetCount,
            ConnectLogger logger) {
        this.direction = implementationType + (toServer ? " -> Server" : " -> Player");
        this.packetCount = packetCount;
        this.logger = logger;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        try {
            if (packetCount.getAndIncrement() < Utils.MAX_DEBUG_PACKET_COUNT) {
                logger.info("{} {}:\n{}", direction, packetCount.get(), describe(msg));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        ctx.write(msg, promise);
    }

    private static String describe(Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            return ByteBufUtil.prettyHexDump(buf);
        }
        return "(pre-encoding) " + msg.getClass().getName() + ": " + msg;
    }
}
