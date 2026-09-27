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

import com.minekube.connect.util.NmsDiagnostics;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Optional;

final class SpigotChatSessionPacketFilter extends ChannelInboundHandlerAdapter {
    static final String HANDLER_NAME = "connect_chat_session_filter";

    private static final String CHAT_SESSION_UPDATE_PACKET =
            "net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket";
    private static final String CHAT_ACK_PACKET =
            "net.minecraft.network.protocol.game.ServerboundChatAckPacket";
    private static final String SIGNED_COMMAND_PACKET =
            "net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket";
    private static final String UNSIGNED_COMMAND_PACKET =
            "net.minecraft.network.protocol.game.ServerboundChatCommandPacket";
    private static final String CHAT_PACKET =
            "net.minecraft.network.protocol.game.ServerboundChatPacket";
    private static final String MESSAGE_SIGNATURE =
            "net.minecraft.network.chat.MessageSignature";
    private static final String LAST_SEEN_UPDATE =
            "net.minecraft.network.chat.LastSeenMessages$Update";
    private static final String OPTIONAL = Optional.class.getName();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Object replacement = rewrite(msg);
        if (replacement == null) {
            ReferenceCountUtil.release(msg);
            return;
        }
        super.channelRead(ctx, replacement);
    }

    private static Object rewrite(Object packet) throws Exception {
        if (isInstance(CHAT_SESSION_UPDATE_PACKET, packet) || isInstance(CHAT_ACK_PACKET, packet)) {
            return null;
        }
        if (isInstance(SIGNED_COMMAND_PACKET, packet)) {
            return rewriteSignedCommand(packet);
        }
        if (isInstance(CHAT_PACKET, packet)) {
            return rewriteChatLastSeen(packet);
        }
        return packet;
    }

    private static Object rewriteSignedCommand(Object packet) throws Exception {
        String command = (String) invoke(packet, "command");
        Constructor<?> constructor = classForName(UNSIGNED_COMMAND_PACKET)
                .getConstructor(String.class);
        return constructor.newInstance(command);
    }

    private static Object rewriteChatLastSeen(Object packet) throws Exception {
        Object lastSeenMessages = invoke(packet, "lastSeenMessages");
        Object emptyLastSeen = emptyLastSeenUpdate();
        Class<?> chatPacket = classForName(CHAT_PACKET);
        // The signature component is a raw MessageSignature on 1.20.x and below and an
        // Optional<MessageSignature> from the 1.21.x line onwards (Paper 26.3 verified), so the
        // constructor has to be resolved from the runtime value instead of a hard-coded type.
        Object signature = invoke(packet, "signature");
        Constructor<?> constructor = chatConstructor(chatPacket, signature, emptyLastSeen);
        if (constructor == null) {
            throw NmsDiagnostics.missingAccessor(
                    CHAT_PACKET + ".<init>(String, Instant, long, <signature>, " + LAST_SEEN_UPDATE + ")",
                    "Tried: signature=" + describe(signature) + ", lastSeenMessages=" + describe(lastSeenMessages)
                            + ".");
        }
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        return constructor.newInstance(
                invoke(packet, "message"),
                invoke(packet, "timeStamp"),
                invoke(packet, "salt"),
                coerceSignature(parameterTypes[3], signature),
                emptyLastSeen
        );
    }

    /**
     * Picks the {@code (String, Instant, long, signature, LastSeenMessages.Update)} constructor whose
     * signature parameter accepts the packet's own signature value.
     */
    static Constructor<?> chatConstructor(Class<?> chatPacket, Object signature, Object lastSeenMessages) {
        for (Constructor<?> constructor : chatPacket.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length != 5) {
                continue;
            }
            if (parameterTypes[0] != String.class
                    || parameterTypes[1] != Instant.class
                    || parameterTypes[2] != long.class) {
                continue;
            }
            if (!parameterTypes[4].isInstance(lastSeenMessages)) {
                continue;
            }
            if (acceptsSignature(parameterTypes[3], signature)) {
                return constructor;
            }
        }
        return null;
    }

    private static boolean acceptsSignature(Class<?> parameterType, Object signature) {
        if (signature == null) {
            // A null signature is assignable to both shapes; prefer the historical raw component so
            // the older servers keep the rewrite they had before.
            return parameterType.getName().equals(MESSAGE_SIGNATURE);
        }
        return parameterType.isInstance(signature);
    }

    private static Object coerceSignature(Class<?> parameterType, Object signature) {
        if (signature != null && parameterType.isInstance(signature)) {
            return signature;
        }
        if (parameterType.getName().equals(OPTIONAL)) {
            return Optional.ofNullable(signature);
        }
        return signature;
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    /**
     * Builds an empty last-seen update for whichever shape the running server provides
     * ({@code (int, BitSet)} or {@code (int, BitSet, byte)}), preferring the newest one.
     */
    private static Object emptyLastSeenUpdate() throws Exception {
        Class<?> lastSeenUpdate = classForName(LAST_SEEN_UPDATE);
        Constructor<?>[] constructors = lastSeenUpdate.getConstructors();
        Arrays.sort(constructors, Comparator.comparingInt((Constructor<?> it) -> it.getParameterCount()).reversed());
        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            boolean supported = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (parameterTypes[i] == int.class) {
                    arguments[i] = 0;
                } else if (parameterTypes[i] == BitSet.class) {
                    arguments[i] = new BitSet();
                } else if (parameterTypes[i] == byte.class) {
                    arguments[i] = (byte) 0;
                } else {
                    supported = false;
                    break;
                }
            }
            if (supported) {
                return constructor.newInstance(arguments);
            }
        }
        throw NmsDiagnostics.missingAccessor(
                LAST_SEEN_UPDATE + ".<init>(int, BitSet[, byte])",
                "Tried: " + constructors.length + " constructors.");
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private static boolean isInstance(String className, Object value) {
        try {
            return classForName(className).isInstance(value);
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static Class<?> classForName(String className) throws ClassNotFoundException {
        return Class.forName(className);
    }
}
