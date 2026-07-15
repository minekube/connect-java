package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minekube.connect.config.ConnectConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class BedrockIdentityConfigurationTest {
    @Test
    void acceptsOnlyExactClosedConfigurationValues() {
        ConnectConfig.BedrockIdentityConfig config = new ConnectConfig().getBedrockIdentity();

        for (String enforcement : new String[] {"warn", "require"}) {
            setField(config, "enforcement", enforcement);
            assertTrue(BedrockIdentityConfiguration.from(config).isUsable());
        }

        for (String enforcement : new String[] {"WARN", "REQUIRE", "warn ", "require\n", "unknown"}) {
            setField(config, "enforcement", enforcement);
            assertFalse(BedrockIdentityConfiguration.from(config).isUsable());
        }

        setField(config, "enforcement", "require");
        for (String policy : new String[] {"linked_java_only", "trusted_bedrock_xuid"}) {
            setField(config, "expectedPolicy", policy);
            assertTrue(BedrockIdentityConfiguration.from(config).isUsable());
        }
        for (String policy : new String[] {"TRUSTED_BEDROCK_XUID", "trusted_bedrock_xuid ", "unknown"}) {
            setField(config, "expectedPolicy", policy);
            assertFalse(BedrockIdentityConfiguration.from(config).isUsable());
        }
    }

    @Test
    void requiresNonblankIssuerWithoutNormalizingIt() {
        ConnectConfig.BedrockIdentityConfig config = new ConnectConfig().getBedrockIdentity();
        setField(config, "enforcement", "require");

        for (String issuer : new String[] {"", " ", "\t\n"}) {
            setField(config, "expectedIssuer", issuer);
            assertFalse(BedrockIdentityConfiguration.from(config).isUsable());
        }

        String issuer = " minekube-connect ";
        setField(config, "expectedIssuer", issuer);
        BedrockIdentityConfiguration configuration = BedrockIdentityConfiguration.from(config);
        assertTrue(configuration.isUsable());
        assertEquals(issuer, configuration.issuer());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
