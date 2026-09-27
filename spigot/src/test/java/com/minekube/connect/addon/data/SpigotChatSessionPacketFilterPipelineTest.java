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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.minekube.connect.config.ConnectConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The customer's shape, asserted at the layer that actually ends the tunneled session.
 *
 * <p>A Connect-tunneled Spigot player runs on the injected local channel, whose pipeline is
 * {@code decoder -> connect_data_handler -> connect_chat_session_filter -> <vanilla network
 * manager>} ({@code SpigotDataAddon.onInject}). Chat arrives <em>after</em> spawn, so a handler that
 * cannot handle a 26.3 packet shape never fails the login: it throws with the player already in
 * play, the vanilla network manager disconnects them ({@code lost connection: Internal Exception:
 * ...}), and the injected channel close ends the tunnel - the ~1 s post-spawn teardown the
 * connector's lines then report as {@code Connect tunneled player X disconnected}.
 *
 * <p>On connect-spigot 0.15.14 the filter resolved {@code ServerboundChatPacket}'s constructor from
 * hard-coded parameter types, while Minecraft had moved the signature component to
 * {@code Optional<MessageSignature>}. Every plain post-spawn chat packet from a 26.3 client
 * therefore threw inside the pipeline; reproduced live against Paper 26.3 through the Connect edge,
 * that closed the session 1.23 s after spawn with
 * {@code NoSuchMethodException: ServerboundChatPacket.<init>(String,Instant,long,MessageSignature,
 * LastSeenMessages$Update)} (fixed by connect-java#170, released in 0.15.15).
 *
 * <p>These tests guard the pipeline, not just the rewrite: the post-spawn packet sequence must
 * leave the session open, on 26.3 and unchanged for the older packet shapes.
 */
class SpigotChatSessionPacketFilterPipelineTest {
    private final List<Object> forwarded = new ArrayList<>();
    private final List<Throwable> escaping = new ArrayList<>();
    private EmbeddedChannel channel;

    @BeforeEach
    void openTheInjectedLocalChannel() {
        channel = new EmbeddedChannel(
                new ForwardingDataHandler(),
                new SpigotChatSessionPacketFilter(),
                new NetworkManager());
    }

    @AfterEach
    void closeTheInjectedLocalChannel() {
        channel.finishAndReleaseAll();
    }

    @Test
    void postSpawnSignedChatFromA26_3ClientKeepsTheTunneledSessionOpen() {
        MessageSignature signature = new MessageSignature(new byte[256]);
        ServerboundChatPacket sent = chat(Optional.of(signature));

        channel.writeInbound(sent);

        assertSessionStillOpen();
        ServerboundChatPacket rewritten = assertRewrittenChat(sent);
        assertEquals(Optional.of(signature), rewritten.signature());
    }

    @Test
    void postSpawnUnsignedChatFromA26_3ClientKeepsTheTunneledSessionOpen() {
        // The wire shape the live reproduction used: MessageSignature.asOptional() sent as absent.
        ServerboundChatPacket sent = chat(Optional.empty());

        channel.writeInbound(sent);

        assertSessionStillOpen();
        ServerboundChatPacket rewritten = assertRewrittenChat(sent);
        assertEquals(Optional.empty(), rewritten.signature());
    }

    @Test
    void postSpawnChatFromAPreOptionalClientKeepsTheTunneledSessionOpen() {
        // The pre-1.21 component shape: a raw signature and the two-argument last-seen update.
        MessageSignature signature = new MessageSignature();
        ServerboundChatPacket sent = new ServerboundChatPacket(
                "hello", Instant.EPOCH, 1L, signature, new LastSeenMessages.Update(4, new BitSet()));

        channel.writeInbound(sent);

        assertSessionStillOpen();
        ServerboundChatPacket rewritten = assertRewrittenChat(sent);
        assertEquals(signature, rewritten.signature());
    }

    @Test
    void preOptionalServerChatWithoutASignatureStillGetsRewritten() {
        ServerboundChatPacket sent = new ServerboundChatPacket(
                "hello", Instant.EPOCH, 1L, (MessageSignature) null, new LastSeenMessages.Update(4, new BitSet()));

        channel.writeInbound(sent);

        assertSessionStillOpen();
        ServerboundChatPacket rewritten = assertRewrittenChat(sent);
        assertNull(rewritten.signature());
    }

    @Test
    void thePostSpawnPacketSequenceKeepsTheSessionOpenAndDropsSessionOnlyPackets() {
        // What a session sends in the first second after spawn: the chat session handshake, an
        // acknowledgement, then the chat that triggered the live teardown.
        channel.writeInbound(new ServerboundChatSessionUpdatePacket());
        channel.writeInbound(new ServerboundChatAckPacket());
        channel.writeInbound(chat(Optional.empty()));

        assertSessionStillOpen();
        assertEquals(1, forwarded.size(), "only the rewritten chat packet may reach the network manager");
        assertInstanceOf(ServerboundChatPacket.class, forwarded.get(0));
    }

    @Test
    void anUnresolvableChatShapeIsRefusedInsteadOfGuessed() throws Exception {
        // Resolved reflectively so the guard can also be compiled against the revision that has no
        // resolver at all (the hard-coded shape), where the lookup itself is the red.
        Method resolve = SpigotChatSessionPacketFilter.class.getDeclaredMethod(
                "chatConstructor", Class.class, Object.class, Object.class);
        resolve.setAccessible(true);
        Object emptyLastSeen = new LastSeenMessages.Update(0, new BitSet(), (byte) 0);

        // The filter resolves the constructor from the packet's own signature value and must refuse
        // when nothing accepts it - never construct a guess. The rewrite turns a refusal into a
        // diagnostic naming the packet and the shapes tried, so a future Minecraft drift is
        // identifiable from the server console instead of being an anonymous reflective failure.
        assertNull(resolve.invoke(null, ServerboundChatPacket.class, "not-a-signature", emptyLastSeen));

        // ...and keeps resolving every shape that is real (here: the 26.3 component type).
        assertNotNull(resolve.invoke(null, ServerboundChatPacket.class, Optional.empty(), emptyLastSeen));
        assertNotNull(resolve.invoke(null, ServerboundChatPacket.class, new MessageSignature(), emptyLastSeen));
    }

    /**
     * The filter itself must never be the node that kills a session: no throwable may escape a
     * packet the connector can handle, and the session must still be open afterwards.
     */
    private void assertSessionStillOpen() {
        assertTrue(escaping.isEmpty(), "a post-spawn packet threw out of the chat filter: " + escaping);
        assertTrue(channel.isActive(), "the tunneled session closed after a post-spawn chat packet");
    }

    private ServerboundChatPacket assertRewrittenChat(ServerboundChatPacket sent) {
        assertEquals(1, forwarded.size(), "the chat packet must reach the network manager exactly once");
        ServerboundChatPacket rewritten = assertInstanceOf(ServerboundChatPacket.class, forwarded.get(0));
        assertNotSame(sent, rewritten, "the rewrite must replace the packet, not mutate it in place");
        assertEquals("hello", rewritten.message());
        assertEquals(Instant.EPOCH, rewritten.timeStamp());
        assertEquals(1L, rewritten.salt());
        assertEquals(0, rewritten.lastSeenMessages().offset());
        assertTrue(rewritten.lastSeenMessages().acknowledged().isEmpty());
        assertEquals(0, rewritten.lastSeenMessages().checksum());
        return rewritten;
    }

    private static ServerboundChatPacket chat(Optional<MessageSignature> signature) {
        return new ServerboundChatPacket(
                "hello", Instant.EPOCH, 1L, signature, new LastSeenMessages.Update(4, new BitSet(), (byte) 7));
    }

    /**
     * The connector's own node in the pipeline ({@code SpigotDataAddon.onInject} adds it directly
     * before the vanilla network manager). Modelled as the smallest {@link CommonDataHandler}
     * subclass because the real {@code SpigotDataHandler} resolves a live server's internals in its
     * static initializer; the pipeline-relevant behaviour of the node is {@code CommonDataHandler}'s
     * forward-or-close.
     */
    private static final class ForwardingDataHandler extends CommonDataHandler {
        ForwardingDataHandler() {
            super(mock(ConnectConfig.class));
        }

        @Override
        protected boolean channelRead(Object packet) {
            return true;
        }
    }

    /**
     * Stands in for the vanilla network manager. Paper's manager answers any throwable that escapes
     * a pipeline handler with {@code Internal Exception: <cause>} and closes the connection, which
     * is what closes the injected local channel and therefore the tunnel.
     */
    private final class NetworkManager extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            forwarded.add(msg);
            ctx.fireChannelRead(msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            escaping.add(cause);
            ctx.close();
        }
    }
}
