package com.minekube.connect.api.player.bedrock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class BedrockIdentityReplayCache {
    private final Map<String, Long> seen = new HashMap<>();

    synchronized boolean accept(String endpointId, String sessionId, String nonce, long nowUnixMs, long expiresAtUnixMs) {
        Iterator<Map.Entry<String, Long>> iterator = seen.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= nowUnixMs) {
                iterator.remove();
            }
        }

        String key = endpointId + "\0" + sessionId + "\0" + nonce;
        if (seen.containsKey(key)) {
            return false;
        }
        seen.put(key, expiresAtUnixMs);
        return true;
    }
}
