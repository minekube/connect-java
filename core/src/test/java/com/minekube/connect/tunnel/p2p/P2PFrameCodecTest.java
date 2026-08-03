package com.minekube.connect.tunnel.p2p;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerRegisterResult;
import minekube.connect.v1alpha1.ConnectLibp2P.SessionAck;
import minekube.connect.v1alpha1.ConnectLibp2P.SessionResponse;
import org.junit.jupiter.api.Test;

class P2PFrameCodecTest {
    @Test
    void roundTripsFrozenKindPrefixedFraming() throws Exception {
        PeerRegisterResult message = PeerRegisterResult.newBuilder().setKvRevision(42).build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        P2PFrameCodec.writeKindPrefixed(out, P2PFrameCodec.RENEWAL_RESULT, message);
        P2PFrameCodec.KindPrefixedFrame frame = P2PFrameCodec.readKindPrefixed(
                new ByteArrayInputStream(out.toByteArray()));

        assertEquals(P2PFrameCodec.RENEWAL_RESULT, frame.kind());
        assertEquals(message, frame.parse(PeerRegisterResult.parser()));
        assertEquals(1 + message.getSerializedSize(), out.toByteArray()[0]);
    }

    @Test
    void kindPrefixedFramingRejectsUnknownAndOversizedFrames() {
        assertThrows(IllegalArgumentException.class, () -> P2PFrameCodec.writeKindPrefixed(
                new ByteArrayOutputStream(), (byte) 0x05, PeerRegisterResult.getDefaultInstance()));
        byte[] oversized = new byte[] {(byte) 0x81, 0x20}; // 4097
        assertThrows(IllegalArgumentException.class, () -> P2PFrameCodec.readKindPrefixed(
                new ByteArrayInputStream(oversized)));
    }

    @Test
    void roundTripsVarintDelimitedProtoFrames() throws Exception {
        SessionResponse response = SessionResponse.newBuilder()
                .setSessionId("session-1")
                .setAck(SessionAck.getDefaultInstance())
                .build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        P2PFrameCodec.write(out, response);
        SessionResponse got = P2PFrameCodec.read(
                new ByteArrayInputStream(out.toByteArray()),
                SessionResponse.parser(),
                P2PFrameCodec.MAX_CONTROL_FRAME_SIZE);

        assertEquals(response, got);
    }

    @Test
    void usesSingleByteLengthForSmallFrames() throws Exception {
        SessionResponse response = SessionResponse.newBuilder()
                .setSessionId("a")
                .setAck(SessionAck.getDefaultInstance())
                .build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        P2PFrameCodec.write(out, response);

        byte[] payload = response.toByteArray();
        byte[] expected = new byte[payload.length + 1];
        expected[0] = (byte) payload.length;
        System.arraycopy(payload, 0, expected, 1, payload.length);
        assertArrayEquals(expected, out.toByteArray());
    }

    @Test
    void rejectsOversizedFrames() {
        byte[] encoded = new byte[] {(byte) 0x81, (byte) 0x80, (byte) 0x40};

        assertThrows(IllegalArgumentException.class, () -> P2PFrameCodec.read(
                new ByteArrayInputStream(encoded),
                SessionResponse.parser(),
                P2PFrameCodec.MAX_CONTROL_FRAME_SIZE));
    }
}
