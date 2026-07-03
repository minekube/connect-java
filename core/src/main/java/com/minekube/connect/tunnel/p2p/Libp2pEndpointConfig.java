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

import io.libp2p.core.multiformats.Multiaddr;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class Libp2pEndpointConfig {
    static final String EDGE_ADDR_ENV = "CONNECT_LIBP2P_EDGE_ADDR";
    static final String LISTEN_ADDR_ENV = "CONNECT_LIBP2P_LISTEN_ADDR";
    static final String ADVERTISE_ADDRS_ENV = "CONNECT_LIBP2P_ADVERTISE_ADDRS";
    static final String RELAY_ADDRS_ENV = "CONNECT_LIBP2P_RELAY_ADDRS";
    private static final List<String> LEGACY_WATCH_CAPABILITIES = Collections.unmodifiableList(Arrays.asList("session", "status"));
    private static final List<String> WATCHLESS_CAPABILITIES = Collections.unmodifiableList(Arrays.asList("session", "status", "watchless"));

    private final List<String> registerAddrs;
    private final List<String> listenAddrs;
    private final List<String> advertiseAddrs;
    private final List<String> relayAddrs;
    private final List<String> capabilities;
    private final boolean watchless;

    private Libp2pEndpointConfig(
            List<String> registerAddrs,
            List<String> listenAddrs,
            List<String> advertiseAddrs,
            List<String> relayAddrs,
            List<String> capabilities,
            boolean watchless) {
        this.registerAddrs = registerAddrs;
        this.listenAddrs = listenAddrs;
        this.advertiseAddrs = advertiseAddrs;
        this.relayAddrs = relayAddrs;
        this.capabilities = capabilities;
        this.watchless = watchless;
    }

    static Libp2pEndpointConfig fromEnvironment(Map<String, String> env) {
        List<String> registerAddrs = split(env.get(EDGE_ADDR_ENV));
        List<String> listenAddrs = split(env.get(LISTEN_ADDR_ENV));
        if (listenAddrs.isEmpty()) {
            listenAddrs = Collections.singletonList("/ip4/127.0.0.1/tcp/0");
        }
        return new Libp2pEndpointConfig(
                registerAddrs,
                listenAddrs,
                split(env.get(ADVERTISE_ADDRS_ENV)),
                split(env.get(RELAY_ADDRS_ENV)),
                LEGACY_WATCH_CAPABILITIES,
                false);
    }

    static Libp2pEndpointConfig fromSystemEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static Libp2pEndpointConfig fromBootstrap(List<String> registerAddrs, List<String> relayAddrs) {
        return fromBootstrap(registerAddrs, relayAddrs, false);
    }

    static Libp2pEndpointConfig fromBootstrap(
            List<String> registerAddrs,
            List<String> relayAddrs,
            boolean watchless) {
        return new Libp2pEndpointConfig(
                immutableCopy(registerAddrs),
                Collections.singletonList("/ip4/127.0.0.1/tcp/0"),
                Collections.emptyList(),
                immutableCopy(relayAddrs),
                watchless ? WATCHLESS_CAPABILITIES : LEGACY_WATCH_CAPABILITIES,
                watchless);
    }

    boolean enabled() {
        return !registerAddrs.isEmpty();
    }

    List<String> registerAddrs() {
        return registerAddrs;
    }

    List<String> listenAddrs() {
        return listenAddrs;
    }

    List<String> advertiseAddrs() {
        return advertiseAddrs;
    }

    List<String> relayAddrs() {
        return relayAddrs;
    }

    List<String> capabilities() {
        return capabilities;
    }

    boolean watchless() {
        return watchless;
    }

    List<String> edgePeerIds() {
        List<String> peerIds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        appendPeerIds(peerIds, seen, registerAddrs);
        appendPeerIds(peerIds, seen, relayAddrs);
        return peerIds;
    }

    private static void appendPeerIds(List<String> peerIds, Set<String> seen, List<String> addresses) {
        for (String address : addresses) {
            String peerId = Multiaddr.fromString(address).getPeerId().toBase58();
            if (seen.add(peerId)) {
                peerIds.add(peerId);
            }
        }
    }

    private static List<String> split(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.toList());
    }

    private static List<String> immutableCopy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(values.stream()
                .filter(value -> value != null && !value.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toList()));
    }
}
