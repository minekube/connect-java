package net.minecraft.network.protocol.game;

import java.time.Instant;
import java.util.Optional;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.MessageSignature;

/**
 * Test stub of the server-side {@code ServerboundChatPacket} record.
 *
 * <p>Minecraft changed this record's {@code signature} component from a raw {@link MessageSignature}
 * to an {@link Optional} of it in the 1.21.x line (verified against Paper 26.3 by {@code javap}:
 * {@code ServerboundChatPacket(String, Instant, long, Optional<MessageSignature>,
 * LastSeenMessages$Update)}). The stub carries both constructors and keeps the component as
 * {@link Object} on purpose, so a test can build the packet the way the runtime in question does and
 * assert that the connector's reflective rewrite resolves the matching constructor from the
 * <em>runtime</em> value type, not from a hard-coded parameter type.
 */
public final class ServerboundChatPacket {
    private final String message;
    private final Instant timeStamp;
    private final long salt;
    private final Object signature;
    private final LastSeenMessages.Update lastSeenMessages;

    public ServerboundChatPacket(
            String message,
            Instant timeStamp,
            long salt,
            MessageSignature signature,
            LastSeenMessages.Update lastSeenMessages
    ) {
        this(message, timeStamp, salt, (Object) signature, lastSeenMessages);
    }

    public ServerboundChatPacket(
            String message,
            Instant timeStamp,
            long salt,
            Optional<MessageSignature> signature,
            LastSeenMessages.Update lastSeenMessages
    ) {
        this(message, timeStamp, salt, (Object) signature, lastSeenMessages);
    }

    private ServerboundChatPacket(
            String message,
            Instant timeStamp,
            long salt,
            Object signature,
            LastSeenMessages.Update lastSeenMessages
    ) {
        this.message = message;
        this.timeStamp = timeStamp;
        this.salt = salt;
        this.signature = signature;
        this.lastSeenMessages = lastSeenMessages;
    }

    public String message() {
        return message;
    }

    public Instant timeStamp() {
        return timeStamp;
    }

    public long salt() {
        return salt;
    }

    public Object signature() {
        return signature;
    }

    public LastSeenMessages.Update lastSeenMessages() {
        return lastSeenMessages;
    }
}
