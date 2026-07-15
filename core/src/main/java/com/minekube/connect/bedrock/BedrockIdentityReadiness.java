package com.minekube.connect.bedrock;

import com.minekube.connect.config.ConnectConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Determines whether this connector can safely receive trusted Bedrock identity envelopes. */
public final class BedrockIdentityReadiness {
    public static final String CAPABILITY = "bedrock-identity-v1";

    private final ConnectConfig config;
    private final BedrockIdentityKeyProvider keyProvider;
    private boolean lastReady;

    public BedrockIdentityReadiness(ConnectConfig config, BedrockIdentityKeyProvider keyProvider) {
        this.config = Objects.requireNonNull(config, "config");
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider");
    }

    public synchronized boolean isReady() {
        boolean ready = currentReady();
        lastReady = ready;
        return ready;
    }

    /** Returns true only when an advertised capability must be withdrawn or re-added. */
    public synchronized boolean refresh() {
        boolean ready = currentReady();
        boolean changed = ready != lastReady;
        lastReady = ready;
        return changed;
    }

    public List<String> capabilities(List<String> configuredCapabilities) {
        List<String> capabilities = new ArrayList<>(configuredCapabilities);
        capabilities.removeIf(CAPABILITY::equals);
        if (isReady()) {
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
