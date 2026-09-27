package net.minecraft.network.chat;

public final class MessageSignature {
    private final byte[] bytes;

    public MessageSignature() {
        this(new byte[0]);
    }

    public MessageSignature(byte[] bytes) {
        this.bytes = bytes;
    }

    public byte[] bytes() {
        return bytes;
    }
}
