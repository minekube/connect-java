package net.minecraft.network.chat;

import java.util.BitSet;

public final class LastSeenMessages {
    private LastSeenMessages() {
    }

    public static final class Update {
        private final int offset;
        private final BitSet acknowledged;

        public Update(int offset, BitSet acknowledged) {
            this.offset = offset;
            this.acknowledged = acknowledged;
        }

        public int offset() {
            return offset;
        }

        public BitSet acknowledged() {
            return acknowledged;
        }
    }
}
