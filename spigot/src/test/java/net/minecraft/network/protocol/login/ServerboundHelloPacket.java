package net.minecraft.network.protocol.login;

import java.util.UUID;

/**
 * Test stub of the server-side {@code ServerboundHelloPacket} (Mojang mappings; on pre-1.20.5
 * obfuscated servers the same packet is {@code PacketLoginInStart}).
 *
 * <p>Connect's login-stall watchdog identifies packets by class name - the convention of
 * {@code SpigotChatSessionPacketFilter} - so a test can drive the real handler with this stub
 * instead of the reflection-based {@code ClassNames} surface, which needs a running server.
 */
public final class ServerboundHelloPacket {
    private final String name;
    private final UUID profileId;

    public ServerboundHelloPacket(String name, UUID profileId) {
        this.name = name;
        this.profileId = profileId;
    }

    public String name() {
        return name;
    }

    public UUID profileId() {
        return profileId;
    }
}
