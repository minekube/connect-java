package net.minecraft.network.protocol.handshake;

/**
 * Test stub of the server-side {@code ClientIntentionPacket} (Mojang mappings; on pre-1.20.5
 * obfuscated servers the same packet is {@code PacketHandshakingInSetProtocol}).
 *
 * <p>Connect's login-stall watchdog identifies packets by class name - the convention of
 * {@code SpigotChatSessionPacketFilter} - so a test can drive the real handler with this stub
 * instead of the reflection-based {@code ClassNames} surface, which needs a running server.
 */
public final class ClientIntentionPacket {
    private final int protocolVersion;
    private final String hostName;
    private final int port;

    public ClientIntentionPacket(int protocolVersion, String hostName, int port) {
        this.protocolVersion = protocolVersion;
        this.hostName = hostName;
        this.port = port;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public String hostName() {
        return hostName;
    }

    public int port() {
        return port;
    }
}
