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
            RemoteKeys remoteKeys = fetchKeys(metadataUrl);
            if (!remoteKeys.keys.isEmpty()) {
                int cacheSeconds = metadataCacheSeconds(remoteKeys.cacheSeconds);
                if (cacheSeconds <= 0) {
                    cacheSeconds = 300;
                }
                Instant fetchedAt = now.get();
                cachedKeys = new CachedKeys(remoteKeys.keys, fetchedAt.plusSeconds(cacheSeconds),
                        fetchedAt.plusSeconds(cacheSeconds + metadataMaxStaleSeconds()));
                return remoteKeys.keys;
            }
        } catch (IOException | JsonSyntaxException | IllegalArgumentException ignored) {
            if (cachedKeys != null && !cachedKeys.keys.isEmpty() && now.get().isBefore(cachedKeys.staleUntil)) {
                return cachedKeys.keys;
            }
        }
        return staticKeys;
    }

    private RemoteKeys fetchKeys(String metadataUrl) throws IOException {
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
            if (metadata == null || !"Ed25519".equals(metadata.algorithm) ||
                    !config.getBedrockIdentity().getExpectedIssuer().equals(metadata.issuer)) {
                throw new IllegalArgumentException("metadata scope is invalid");
            }
            List<byte[]> keys = new ArrayList<>();
            addMetadataKey(keys, metadata.current_public_key);
            if (metadata.previous_public_keys != null) {
                for (String key : metadata.previous_public_keys) {
                    addMetadataKey(keys, key);
                }
            }
            return new RemoteKeys(keys, metadata.cache_max_age_seconds);
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

    private static void addMetadataKey(List<byte[]> keys, String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("metadata key is empty");
        }
        byte[] decoded = Base64.getDecoder().decode(encoded);
        if (decoded.length != 32) {
            throw new IllegalArgumentException("metadata key is not an Ed25519 public key");
        }
        keys.add(decoded);
    }

    private int metadataCacheSeconds(int remoteCacheSeconds) {
        int configured = config.getBedrockIdentity().getMetadataCacheSeconds();
        if (configured <= 0) {
            return remoteCacheSeconds;
        }
        return remoteCacheSeconds <= 0 ? configured : Math.min(configured, remoteCacheSeconds);
    }

    private int metadataMaxStaleSeconds() {
        return Math.max(0, config.getBedrockIdentity().getMetadataMaxStaleSeconds());
    }

    private static final class CachedKeys {
        private final List<byte[]> keys;
        private final Instant expiresAt;
        private final Instant staleUntil;

        private CachedKeys(List<byte[]> keys, Instant expiresAt, Instant staleUntil) {
            this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
            this.expiresAt = expiresAt;
            this.staleUntil = staleUntil;
        }
    }

    private static final class RemoteKeys {
        private final List<byte[]> keys;
        private final int cacheSeconds;

        private RemoteKeys(List<byte[]> keys, int cacheSeconds) {
            this.keys = keys;
            this.cacheSeconds = cacheSeconds;
        }
    }

    private static final class VerifierMetadata {
        String issuer;
        String algorithm;
        String current_public_key;
        List<String> previous_public_keys;
        int cache_max_age_seconds;
    }
}
