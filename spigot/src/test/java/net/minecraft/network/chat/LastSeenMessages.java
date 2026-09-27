package net.minecraft.network.chat;

import java.util.BitSet;

public final class LastSeenMessages {
    private LastSeenMessages() {
    }

    public static final class Update {
        private final int offset;
        private final BitSet acknowledged;
        private final byte checksum;

        public Update(int offset, BitSet acknowledged) {
            this(offset, acknowledged, (byte) 0);
        }

        public Update(int offset, BitSet acknowledged, byte checksum) {
            this.offset = offset;
            this.acknowledged = acknowledged;
            this.checksum = checksum;
        }

        public int offset() {
            return offset;
        }

        public BitSet acknowledged() {
            return acknowledged;
        }

        public byte checksum() {
            return checksum;
        }
    }
}
