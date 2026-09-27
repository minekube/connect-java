package net.minecraft.network.protocol.game;

/**
 * Test stub of the server-side {@code ServerboundChatAckPacket}.
 *
 * <p>The connector's chat filter drops this packet by class name
 * ({@code SpigotChatSessionPacketFilter#rewrite}), so the stub only has to exist for the filter's
 * {@code isInstance} check to be exercised on the drop path.
 */
public final class ServerboundChatAckPacket {
}
