package net.minecraft.network.protocol.game;

import java.time.Instant;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.MessageSignature;

public final class ServerboundChatPacket {
    private final String message;
    private final Instant timeStamp;
    private final long salt;
    private final MessageSignature signature;
    private final LastSeenMessages.Update lastSeenMessages;

    public ServerboundChatPacket(
            String message,
            Instant timeStamp,
            long salt,
            MessageSignature signature,
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

    public MessageSignature signature() {
        return signature;
    }

    public LastSeenMessages.Update lastSeenMessages() {
        return lastSeenMessages;
    }
}
