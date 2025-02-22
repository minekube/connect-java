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

package com.minekube.connect.addon.packethandler;

import com.minekube.connect.api.util.TriFunction;
import com.minekube.connect.packet.PacketHandlersImpl;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.util.List;

public class ChannelOutPacketHandler extends MessageToMessageEncoder<Object> {
    private final PacketHandlersImpl packetHandlers;
    private final boolean toServer;

    public ChannelOutPacketHandler(PacketHandlersImpl packetHandlers, boolean toServer) {
        this.packetHandlers = packetHandlers;
        this.toServer = toServer;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) {
        Object packet = msg;
        for (TriFunction<ChannelHandlerContext, Object, Boolean, Object> consumer :
                packetHandlers.getPacketHandlers(msg.getClass())) {

            Object res = consumer.apply(ctx, msg, toServer);
            if (!res.equals(msg)) {
                packet = res;
            }
        }

        out.add(packet);
    }
}
