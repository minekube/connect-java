package com.minekube.connect.addon.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.minekube.connect.api.player.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpigotDataHandlerTest {
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
                                "")));

        String properties = SpigotDataHandler.forwardedPropertiesJson(profile);

        assertFalse(properties.contains("minekube:bedrock_identity"));
        assertFalse(properties.contains("replay-nonce-a"));
    }
}
