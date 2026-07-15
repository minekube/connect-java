package com.minekube.connect.bedrock;

import com.minekube.connect.config.ConnectConfig;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Determines whether this connector can safely receive trusted Bedrock identity envelopes. */
public final class BedrockIdentityReadiness {
    public static final String CAPABILITY = "bedrock-identity-v1";

    public enum Transport {
        WATCH,
        LIBP2P
    }

    private final ConnectConfig config;
    private final BedrockIdentityKeyProvider keyProvider;
    private final Map<Transport, Boolean> lastReady = new EnumMap<>(Transport.class);

    public BedrockIdentityReadiness(ConnectConfig config, BedrockIdentityKeyProvider keyProvider) {
        this.config = Objects.requireNonNull(config, "config");
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider");
        for (Transport transport : Transport.values()) {
            lastReady.put(transport, false);
        }
    }

    public synchronized boolean isReady() {
        return currentReady();
    }

    public synchronized boolean observe(Transport transport) {
        boolean ready = currentReady();
        lastReady.put(Objects.requireNonNull(transport, "transport"), ready);
        return ready;
    }

    /** Returns true only when an advertised capability must be withdrawn or re-added. */
    public synchronized boolean refresh(Transport transport) {
        boolean ready = currentReady();
        boolean changed = ready != lastReady.put(
                Objects.requireNonNull(transport, "transport"),
                ready);
        return changed;
    }

    public List<String> capabilities(List<String> configuredCapabilities, Transport transport) {
        List<String> capabilities = new ArrayList<>(configuredCapabilities);
        capabilities.removeIf(CAPABILITY::equals);
        if (observe(transport)) {
            capabilities.add(CAPABILITY);
        }
        return capabilities;
    }

    private boolean currentReady() {
        String enforcement = config.getBedrockIdentity().getEnforcement();
        return enforcement != null
                && !"disabled".equals(enforcement.toLowerCase(Locale.ROOT))
                && keyProvider.hasUsableKeys();
    }
}
