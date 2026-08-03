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
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.minekube.connect.tunnel.p2p;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class DirectP2pHostInfo {
    private final String peerId;
    private final byte[] publicKey;
    private final List<String> lanAddresses;
    private final List<String> internetAddresses;

    public DirectP2pHostInfo(
            String peerId,
            byte[] publicKey,
            List<String> lanAddresses,
            List<String> internetAddresses) {
        this.peerId = Objects.requireNonNull(peerId, "peerId");
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey").clone();
        this.lanAddresses = immutableCopy(lanAddresses);
        this.internetAddresses = immutableCopy(internetAddresses);
    }

    public String peerId() {
        return peerId;
    }

    public byte[] publicKey() {
        return publicKey.clone();
    }

    public List<String> lanAddresses() {
        return lanAddresses;
    }

    public List<String> internetAddresses() {
        return internetAddresses;
    }

    @Override
    public String toString() {
        return "DirectP2pHostInfo{peerId='" + peerId
                + "', publicKey=<redacted>, lanAddresses=<redacted>, "
                + "internetAddresses=<redacted>}";
    }

    private static List<String> immutableCopy(List<String> addresses) {
        return Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(addresses, "addresses")));
    }
}
