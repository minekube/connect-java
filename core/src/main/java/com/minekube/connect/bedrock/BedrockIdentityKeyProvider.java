package com.minekube.connect.bedrock;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.minekube.connect.config.ConnectConfig;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class BedrockIdentityKeyProvider {
    private static final Gson GSON = new Gson();

    private final ConnectConfig config;
    private final OkHttpClient httpClient;
    private final Supplier<Instant> now;
    private CachedKeys cachedKeys;

    BedrockIdentityKeyProvider(ConnectConfig config, OkHttpClient httpClient) {
        this(config, httpClient, Instant::now);
    }

    BedrockIdentityKeyProvider(ConnectConfig config, OkHttpClient httpClient, Supplier<Instant> now) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.now = Objects.requireNonNull(now, "now");
    }

    List<byte[]> keys() {
        List<byte[]> staticKeys = staticKeys();
        String metadataUrl = config.getBedrockIdentity().getMetadataUrl();
        if (metadataUrl == null || metadataUrl.isEmpty()) {
            return staticKeys;
        }
        if (cachedKeys != null && now.get().isBefore(cachedKeys.expiresAt)) {
            return cachedKeys.keys;
        }
        try {
            List<byte[]> remoteKeys = fetchKeys(metadataUrl);
            if (!remoteKeys.isEmpty()) {
                int cacheSeconds = config.getBedrockIdentity().getMetadataCacheSeconds();
                if (cacheSeconds <= 0) {
                    cacheSeconds = 300;
                }
                cachedKeys = new CachedKeys(remoteKeys, now.get().plusSeconds(cacheSeconds));
                return remoteKeys;
            }
        } catch (IOException | JsonSyntaxException | IllegalArgumentException ignored) {
            if (cachedKeys != null && !cachedKeys.keys.isEmpty()) {
                return cachedKeys.keys;
            }
        }
        return staticKeys;
    }

    private List<byte[]> fetchKeys(String metadataUrl) throws IOException {
        Request request = new Request.Builder().url(metadataUrl).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("metadata status " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("metadata body is empty");
            }
            VerifierMetadata metadata = GSON.fromJson(body.charStream(), VerifierMetadata.class);
            if (metadata == null || !"Ed25519".equals(metadata.algorithm)) {
                return Collections.emptyList();
            }
            List<byte[]> keys = new ArrayList<>();
            addKey(keys, metadata.current_public_key);
            if (metadata.previous_public_keys != null) {
                for (String key : metadata.previous_public_keys) {
                    addKey(keys, key);
                }
            }
            return keys;
        }
    }

    private List<byte[]> staticKeys() {
        List<byte[]> keys = new ArrayList<>();
        addKey(keys, config.getBedrockIdentity().getPublicKey());
        if (config.getBedrockIdentity().getPublicKeys() != null) {
            for (String key : config.getBedrockIdentity().getPublicKeys()) {
                addKey(keys, key);
            }
        }
        return keys;
    }

    private static void addKey(List<byte[]> keys, String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        byte[] decoded = Base64.getDecoder().decode(encoded);
        keys.add(decoded);
    }

    private static final class CachedKeys {
        private final List<byte[]> keys;
        private final Instant expiresAt;

        private CachedKeys(List<byte[]> keys, Instant expiresAt) {
            this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
            this.expiresAt = expiresAt;
        }
    }

    private static final class VerifierMetadata {
        String algorithm;
        String current_public_key;
        List<String> previous_public_keys;
    }
}
