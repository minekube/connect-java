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

package com.minekube.connect.addon.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.BitSet;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import org.junit.jupiter.api.Test;

class SpigotChatSessionPacketFilterTest {
    @Test
    void rewritesChatPacketWhenLastSeenUpdateHasNoChecksumConstructor() throws Exception {
        MessageSignature signature = new MessageSignature();
        ServerboundChatPacket packet = new ServerboundChatPacket(
                "hello",
                Instant.EPOCH,
                1L,
                signature,
                new LastSeenMessages.Update(4, new BitSet())
        );

        ServerboundChatPacket rewritten = (ServerboundChatPacket) rewrite(packet);

        assertNotSame(packet, rewritten);
        assertEquals("hello", rewritten.message());
        assertEquals(Instant.EPOCH, rewritten.timeStamp());
        assertEquals(1L, rewritten.salt());
        assertEquals(signature, rewritten.signature());
        assertEquals(0, rewritten.lastSeenMessages().offset());
        assertTrue(rewritten.lastSeenMessages().acknowledged().isEmpty());
    }

    private static Object rewrite(Object packet) throws Exception {
        Method method = SpigotChatSessionPacketFilter.class.getDeclaredMethod("rewrite", Object.class);
        method.setAccessible(true);
        try {
            return method.invoke(null, packet);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw exception;
        }
    }
}
