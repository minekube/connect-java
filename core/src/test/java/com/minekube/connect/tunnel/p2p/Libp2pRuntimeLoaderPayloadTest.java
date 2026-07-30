package com.minekube.connect.tunnel.p2p;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Libp2pRuntimeLoaderPayloadTest {
    @Test
    void extractsPayloadToContentHashedTemporaryJarAndDeletesIt() throws Exception {
        byte[] payload = "isolated-runtime".getBytes(StandardCharsets.UTF_8);

        Path extracted = Libp2pRuntimeLoader.extractRuntimePayload(
                new ByteArrayInputStream(payload));
        try {
            assertTrue(extracted.getFileName().toString().matches(
                    "libp2p-runtime-[a-f0-9]{64}\\.jar"));
            assertArrayEquals(payload, Files.readAllBytes(extracted));
        } finally {
            Libp2pRuntimeLoader.deleteRuntimePayload(extracted);
        }

        assertFalse(Files.exists(extracted));
        assertFalse(Files.exists(extracted.getParent()));
    }
}
