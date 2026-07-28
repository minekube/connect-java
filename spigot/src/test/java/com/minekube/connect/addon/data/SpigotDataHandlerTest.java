package com.minekube.connect.addon.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.util.NmsDiagnostics;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SpigotDataHandlerTest {
    private static final String SERVER_NAME = "Paper";
    private static final String MINECRAFT_VERSION = "1.21.4";
    private static final String BUKKIT_VERSION = MINECRAFT_VERSION + "-R0.1-SNAPSHOT";

    @BeforeAll
    static void installServer() {
        if (Bukkit.getServer() != null) {
            return;
        }
        Server server = mock(Server.class);
        lenient().when(server.getName()).thenReturn(SERVER_NAME);
        lenient().when(server.getVersion()).thenReturn("git-Paper-196 (MC: " + MINECRAFT_VERSION + ")");
        lenient().when(server.getBukkitVersion()).thenReturn(BUKKIT_VERSION);
        lenient().when(server.getLogger()).thenReturn(Logger.getLogger(SERVER_NAME));
        Bukkit.setServer(server);
    }

    @Test
    void usesSpoofedPlayerAddressWhenLocalChannelHasNoInetRemoteAddress() {
        String address = SpigotDataHandler.playerRemoteAddressAsString(
                new LocalAddress("connect-local"),
                InetSocketAddress.createUnresolved("93.201.72.37", 0));

        assertEquals("93.201.72.37", address);
    }

    @Test
    void removeSelfIsIdempotentWhenHandshakeReplacementReentersPipeline() throws Exception {
        SpigotDataHandler handler = new SpigotDataHandler(null, "packet-handler", null, null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        Method removeSelf = SpigotDataHandler.class.getDeclaredMethod("removeSelf");
        removeSelf.setAccessible(true);

        removeSelf.invoke(handler);

        assertDoesNotThrow(() -> removeSelf.invoke(handler));
        channel.finishAndReleaseAll();
    }

    @Test
    void bungeeForwardedPropertiesExcludeIdentityEnvelopeAndNonce() {
        GameProfile profile = new GameProfile(
                "BedrockSteve",
                UUID.fromString("f912bf90-8349-565f-9dc0-9891923c0cc3"),
                Arrays.asList(
                        new GameProfile.Property("textures", "skin", "signature"),
                        new GameProfile.Property(
                                "minekube:bedrock_identity",
                                "signed-envelope-replay-nonce-a",
                                ""),
                        new GameProfile.Property(
                                "minekube:bedrock_identity_scope",
                                "private-endpoint-id",
                                "")));

        String properties = SpigotDataHandler.forwardedPropertiesJson(profile);

        assertFalse(properties.contains("minekube:bedrock_identity"));
        assertFalse(properties.contains("minekube:bedrock_identity_scope"));
        assertFalse(properties.contains("replay-nonce-a"));
        assertFalse(properties.contains("private-endpoint-id"));
    }

    @Test
    void missingVelocityFieldIsIgnoredWhenVelocitySupportIsDisabled() {
        assertDoesNotThrow(() -> SpigotDataHandler.setVelocityLoginMessageIdIfSupported(
                new Object(), false, null, TestLoginListener.class));
    }

    @Test
    void missingVelocityFieldNamesTheAccessorWhenVelocitySupportIsEnabled() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SpigotDataHandler.setVelocityLoginMessageIdIfSupported(
                        new Object(), true, null, TestLoginListener.class));

        String message = failure.getMessage();
        assertNotNull(message);
        assertTrue(message.contains(TestLoginListener.class.getName() + "#velocityLoginMessageId"), message);
        assertTrue(message.contains(SERVER_NAME), message);
        assertTrue(message.contains(BUKKIT_VERSION), message);
        assertTrue(message.contains(MINECRAFT_VERSION), message);
        assertTrue(message.contains("java="), message);
        assertTrue(message.contains("not a Java runtime incompatibility"), message);
    }

    private static final class TestLoginListener {
    }
}
