package com.minekube.connect.bedrock;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerifier;
import com.minekube.connect.config.ConnectConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class BedrockIdentityKeyProvider {
    private static final Gson GSON = new Gson();
    private static final long METADATA_BODY_LIMIT_BYTES = 64 * 1024;
    private static final long METADATA_CALL_TIMEOUT_SECONDS = 5;
    private static final long METADATA_FAILURE_BACKOFF_SECONDS = 5;

    private final ConnectConfig config;
    private final OkHttpClient httpClient;
    private final Supplier<Instant> now;
    private CachedKeys cachedKeys;
    private Instant nextRefreshAt = Instant.MIN;

    public BedrockIdentityKeyProvider(ConnectConfig config, OkHttpClient httpClient) {
        this(config, httpClient, Instant::now);
    }

    BedrockIdentityKeyProvider(ConnectConfig config, OkHttpClient httpClient, Supplier<Instant> now) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient").newBuilder()
                .callTimeout(METADATA_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        this.now = Objects.requireNonNull(now, "now");
    }

    synchronized List<byte[]> keys() {
        List<byte[]> staticKeys = staticKeys();
        String metadataUrl = config.getBedrockIdentity().getMetadataUrl();
        if (metadataUrl == null || metadataUrl.isEmpty()) {
            return staticKeys;
        }
        Instant current = now.get();
        if (cachedKeys != null && current.isBefore(cachedKeys.expiresAt)) {
            return cachedKeys.keys;
        }
        if (current.isBefore(nextRefreshAt)) {
            return staleKeys(current);
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
            Instant failedAt = now.get();
            nextRefreshAt = failureBackoffUntil(failedAt);
            return staleKeys(failedAt);
        }
        return Collections.emptyList();
    }

    synchronized boolean hasUsableKeys() {
        List<byte[]> keys = keys();
        String metadataUrl = config.getBedrockIdentity().getMetadataUrl();
        if (metadataUrl == null || metadataUrl.isEmpty()) {
            return !keys.isEmpty();
        }
        Instant current = now.get();
        return cachedKeys != null && !cachedKeys.keys.isEmpty() && current.isBefore(cachedKeys.staleUntil);
    }

    private RemoteKeys fetchKeys(String metadataUrl) throws IOException {
        Request request = new Request.Builder().url(validMetadataUrl(metadataUrl)).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("metadata status " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("metadata body is empty");
            }
            VerifierMetadata metadata = GSON.fromJson(readBody(body), VerifierMetadata.class);
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

    private static HttpUrl validMetadataUrl(String configured) {
        HttpUrl url = HttpUrl.parse(configured);
        if (url == null || !"https".equals(url.scheme()) || !url.username().isEmpty()
                || !url.password().isEmpty() || url.fragment() != null) {
            throw new IllegalArgumentException("metadata URL must be HTTPS without userinfo or fragment");
        }
        return url;
    }

    private String readBody(ResponseBody body) throws IOException {
        try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > METADATA_BODY_LIMIT_BYTES) {
                    throw new IOException("metadata body exceeds limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private List<byte[]> staleKeys(Instant current) {
        if (cachedKeys != null && !cachedKeys.keys.isEmpty() && current.isBefore(cachedKeys.staleUntil)) {
            return cachedKeys.keys;
        }
        return Collections.emptyList();
    }

    private Instant failureBackoffUntil(Instant current) {
        return current.plusSeconds(METADATA_FAILURE_BACKOFF_SECONDS);
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
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            BedrockIdentityVerifier.builder().publicKey(decoded);
            keys.add(decoded);
        } catch (IllegalArgumentException ignored) {
            // Invalid configured material is not a usable verifier key.
        }
    }

    private static void addMetadataKey(List<byte[]> keys, String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("metadata key is empty");
        }
        byte[] decoded = Base64.getDecoder().decode(encoded);
        if (decoded.length != 32) {
            throw new IllegalArgumentException("metadata key is not an Ed25519 public key");
        }
        BedrockIdentityVerifier.builder().publicKey(decoded);
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
