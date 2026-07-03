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
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author Minekube
 * @link https://github.com/minekube/connect-java
 */

package com.minekube.connect.watch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import okhttp3.Headers;

public final class WatchBootstrap {
    public static final String LIBP2P_EDGE_ADDRS_HEADER = "Connect-Libp2p-Edge-Addrs";
    public static final String LIBP2P_RELAY_ADDRS_HEADER = "Connect-Libp2p-Relay-Addrs";
    public static final String LIBP2P_CAPABILITIES_HEADER = "Connect-Libp2p-Capabilities";

    private static final String CAPABILITY_WATCHLESS = "watchless";

    private final List<String> libp2pEdgeAddrs;
    private final List<String> libp2pRelayAddrs;
    private final List<String> libp2pCapabilities;

    private WatchBootstrap(
            List<String> libp2pEdgeAddrs,
            List<String> libp2pRelayAddrs,
            List<String> libp2pCapabilities) {
        this.libp2pEdgeAddrs = immutableCopy(libp2pEdgeAddrs);
        this.libp2pRelayAddrs = immutableCopy(libp2pRelayAddrs);
        this.libp2pCapabilities = immutableCopy(libp2pCapabilities);
    }

    public static WatchBootstrap fromHeaders(Headers headers) {
        return fromLists(
                parseHeaderValues(headers.values(LIBP2P_EDGE_ADDRS_HEADER)),
                parseHeaderValues(headers.values(LIBP2P_RELAY_ADDRS_HEADER)),
                parseHeaderValues(headers.values(LIBP2P_CAPABILITIES_HEADER)));
    }

    public static WatchBootstrap fromLists(
            List<String> libp2pEdgeAddrs,
            List<String> libp2pRelayAddrs,
            List<String> libp2pCapabilities) {
        return new WatchBootstrap(libp2pEdgeAddrs, libp2pRelayAddrs, libp2pCapabilities);
    }

    public boolean hasLibp2p() {
        return !libp2pEdgeAddrs.isEmpty();
    }

    public boolean supportsWatchlessLibp2p() {
        return libp2pCapabilities.contains(CAPABILITY_WATCHLESS);
    }

    public List<String> libp2pEdgeAddrs() {
        return libp2pEdgeAddrs;
    }

    public List<String> libp2pRelayAddrs() {
        return libp2pRelayAddrs;
    }

    private static List<String> parseHeaderValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
        }
        return out;
    }

    private static List<String> immutableCopy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
