/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Floodgate
 */

package com.minekube.connect.identity;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.minekube.connect.util.Utils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class EndpointTokenStore {
    public static final String ENV_TOKEN = "CONNECT_TOKEN";

    private static final Gson GSON = new Gson();
    private static final Set<java.nio.file.attribute.PosixFilePermission> OWNER_ONLY =
            Set.of(OWNER_READ, OWNER_WRITE);

    public Optional<String> load(
            Path tokenFile,
            Map<String, String> environment
    ) throws IOException {
        Objects.requireNonNull(tokenFile, "tokenFile");
        Objects.requireNonNull(environment, "environment");

        String environmentToken = environment.get(ENV_TOKEN);
        if (environmentToken != null) {
            return Optional.of(validate(environmentToken));
        }
        if (!Files.exists(tokenFile)) {
            return Optional.empty();
        }

        try (Reader reader = Files.newBufferedReader(tokenFile, StandardCharsets.UTF_8)) {
            TokenDocument document = GSON.fromJson(reader, TokenDocument.class);
            if (document == null) {
                throw new IllegalArgumentException("Connect token file is empty");
            }
            return Optional.of(validate(document.token));
        } catch (JsonParseException exception) {
            throw new IOException("Connect token file is not valid JSON", exception);
        }
    }

    public String loadOrCreate(
            Path tokenFile,
            Map<String, String> environment
    ) throws IOException {
        Optional<String> existing = load(tokenFile, environment);
        if (existing.isPresent()) {
            return existing.get();
        }

        String token = generate();
        save(tokenFile, token);
        return token;
    }

    public void save(Path tokenFile, String token) throws IOException {
        Objects.requireNonNull(tokenFile, "tokenFile");
        String validToken = validate(token);
        Path target = tokenFile.toAbsolutePath();
        Path parent = Objects.requireNonNull(target.getParent(), "tokenFile parent");
        Files.createDirectories(parent);

        Path temporary = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(new TokenDocument(validToken), writer);
            }
            applyOwnerOnlyPermissions(temporary);
            try {
                Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public String generate() {
        return "T-" + Utils.randomSecureString(20);
    }

    public static String redact(String token) {
        return "<redacted-connect-token>";
    }

    private static String validate(String token) {
        if (token == null
                || token.isBlank()
                || !token.startsWith("T-")
                || token.length() == 2
                || token.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "Connect token must start with T- and contain a non-blank value");
        }
        return token;
    }

    private static void applyOwnerOnlyPermissions(Path file) throws IOException {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        }
    }

    private static final class TokenDocument {
        private final String token;

        private TokenDocument(String token) {
            this.token = token;
        }
    }
}
