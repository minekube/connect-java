package com.minekube.connect.api.player.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.beans.Transient;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BedrockIdentityClaimsExposureTest {
    @Test
    void exposesXuidOnlyThroughTheExplicitSensitiveGetter() {
        String xuid = "12345678901234567";
        BedrockIdentityClaims claims = new BedrockIdentityClaims(
                "minekube-connect", "endpoint-id", "endpoint", "org-id", "session-id",
                "bedrock", "trusted_bedrock_xuid", "unlinked", xuid, "BedrockSteve",
                "f912bf90-8349-565f-9dc0-9891923c0cc3", null, null,
                null, null);

        assertEquals(xuid, claims.getBedrockXuid());
        assertFalse(claims.toString().contains(xuid));
        assertFalse(new GsonBuilder()
                .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>)
                        (value, type, context) -> new JsonPrimitive(value.toString()))
                .create()
                .toJson(claims)
                .contains(xuid));
        assertFalse(claims.toString().contains("signed-envelope"));
        assertFalse(claims.toString().contains("replay-nonce"));
        assertFalse(claims.toString().contains("bedrock_identity_scope"));
    }

    @Test
    void excludesSensitiveXuidFromGetterBasedSerialization() throws Exception {
        String xuid = "12345678901234567";
        BedrockIdentityClaims claims = new BedrockIdentityClaims(
                "minekube-connect", "endpoint-id", "endpoint", "org-id", "session-id",
                "bedrock", "trusted_bedrock_xuid", "unlinked", xuid, "BedrockSteve",
                "f912bf90-8349-565f-9dc0-9891923c0cc3", null, null,
                null, null);

        Method getter = BedrockIdentityClaims.class.getMethod("getBedrockXuid");
        Map<String, Object> serialized = new HashMap<>();
        for (PropertyDescriptor property :
                Introspector.getBeanInfo(BedrockIdentityClaims.class, Object.class)
                        .getPropertyDescriptors()) {
            Method readMethod = property.getReadMethod();
            if (readMethod != null && !readMethod.isAnnotationPresent(Transient.class)) {
                serialized.put(property.getName(), invoke(readMethod, claims));
            }
        }

        assertTrue(getter.isAnnotationPresent(Transient.class));
        assertFalse(serialized.containsKey("bedrockXuid"));
        assertFalse(serialized.containsValue(xuid));
    }

    private static Object invoke(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
