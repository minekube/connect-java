/*
 * Copyright (c) 2021-2022 Minekube. https://minekube.com
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
 * DEALINGS IN THE SOFTWARE.
 *
 * @author Minekube
 * @link https://github.com/minekube/connect-java
 */

package com.minekube.connect.tunnel.p2p;

import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.crypto.PrivKey;
import io.libp2p.core.crypto.PubKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import kotlin.Pair;

final class EndpointPeerIdentity {
    private final PrivKey privateKey;
    private final PubKey publicKey;
    private final String peerId;
    private final String publicKeyBase64;

    private EndpointPeerIdentity(PrivKey privateKey) {
        this.privateKey = privateKey;
        this.publicKey = privateKey.publicKey();
        this.peerId = PeerId.fromPubKey(publicKey).toString();
        this.publicKeyBase64 = Base64.getEncoder().encodeToString(KeyKt.marshalPublicKey(publicKey));
    }

    static EndpointPeerIdentity loadOrCreate(Path keyFile) throws IOException {
        if (Files.exists(keyFile)) {
            return new EndpointPeerIdentity(KeyKt.unmarshalPrivateKey(Files.readAllBytes(keyFile)));
        }
        Path parent = keyFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Pair<PrivKey, PubKey> keyPair = KeyKt.generateKeyPair(KeyType.ED25519);
        PrivKey privateKey = keyPair.getFirst();
        writePrivateKey(keyFile, KeyKt.marshalPrivateKey(privateKey));
        return new EndpointPeerIdentity(privateKey);
    }

    private static void writePrivateKey(Path keyFile, byte[] data) throws IOException {
        Path parent = keyFile.getParent();
        if (parent == null) {
            parent = keyFile.toAbsolutePath().getParent();
        }
        Path temp = Files.createTempFile(parent, keyFile.getFileName().toString(), ".tmp");
        try {
            setOwnerOnlyPermissions(temp);
            Files.write(temp, data, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, keyFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, keyFile);
            }
            setOwnerOnlyPermissions(keyFile);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void setOwnerOnlyPermissions(Path file) throws IOException {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems, notably Windows, do not expose Unix mode bits.
        }
    }

    String peerId() {
        return peerId;
    }

    String publicKeyBase64() {
        return publicKeyBase64;
    }

    byte[] sign(byte[] payload) {
        return privateKey.sign(payload);
    }

    PrivKey privateKey() {
        return privateKey;
    }
}
