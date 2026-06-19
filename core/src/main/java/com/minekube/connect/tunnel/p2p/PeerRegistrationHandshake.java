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

import com.google.protobuf.ByteString;
import io.libp2p.core.multiformats.Multiaddr;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import minekube.connect.v1alpha1.ConnectLibp2P.EndpointPeerRecord;
import minekube.connect.v1alpha1.ConnectLibp2P.OfflineMode;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerCapacity;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerRegisterChallenge;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerRegisterCommit;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerRegisterInit;

final class PeerRegistrationHandshake {
    private final EndpointPeerIdentity identity;
    private final String endpoint;
    private final String token;
    private final String endpointInstanceId;
    private final List<String> parentEndpoints;
    private final OfflineMode offlineMode;
    private final List<String> capabilities;
    private final Supplier<PeerCapacity> capacitySupplier;

    PeerRegistrationHandshake(
            EndpointPeerIdentity identity,
            String endpoint,
            String token,
            String endpointInstanceId,
            List<String> parentEndpoints,
            OfflineMode offlineMode,
            List<String> capabilities,
            PeerCapacity capacity) {
        this(identity, endpoint, token, endpointInstanceId, parentEndpoints, offlineMode, capabilities, () -> capacity);
    }

    PeerRegistrationHandshake(
            EndpointPeerIdentity identity,
            String endpoint,
            String token,
            String endpointInstanceId,
            List<String> parentEndpoints,
            OfflineMode offlineMode,
            List<String> capabilities,
            Supplier<PeerCapacity> capacitySupplier) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.token = Objects.requireNonNull(token, "token");
        this.endpointInstanceId = Objects.requireNonNull(endpointInstanceId, "endpointInstanceId");
        this.parentEndpoints = new ArrayList<>(Objects.requireNonNull(parentEndpoints, "parentEndpoints"));
        this.offlineMode = Objects.requireNonNull(offlineMode, "offlineMode");
        this.capabilities = new ArrayList<>(Objects.requireNonNull(capabilities, "capabilities"));
        this.capacitySupplier = Objects.requireNonNull(capacitySupplier, "capacitySupplier");
    }

    PeerRegisterInit init(List<String> observedAddrs) {
        long now = System.currentTimeMillis();
        return PeerRegisterInit.newBuilder()
                .setEndpoint(endpoint)
                .setToken(token)
                .setEndpointInstanceId(endpointInstanceId)
                .setEndpointPeerId(identity.peerId())
                .setEndpointPublicKey(identity.publicKeyBase64())
                .addAllParentEndpoints(parentEndpoints)
                .setOfflineMode(offlineMode)
                .addAllCapabilities(capabilities)
                .addAllObservedAddrs(observedAddrs)
                .setCapacity(capacity())
                .setIssuedAtUnixMs(now)
                .setExpiresAtUnixMs(now + 45_000)
                .build();
    }

    PeerRegisterCommit commit(PeerRegisterChallenge challenge, List<String> addrs, long sequence, long nowUnixMs) {
        long ttlMs = challenge.getKvTtlMs() > 0 ? challenge.getKvTtlMs() : 45_000;
        List<String> recordAddrs = challengedRelayCircuitAddrs(challenge, addrs);
        if (recordAddrs.isEmpty() && challenge.getRelayAddrsList().isEmpty()) {
            recordAddrs = addrs;
        }
        EndpointPeerRecord record = EndpointPeerRecord.newBuilder()
                .setVersion(1)
                .setEndpoint(endpoint)
                .setEndpointHash(challenge.getEndpointHash())
                .setEndpointId(challenge.getEndpointId())
                .setEndpointInstanceId(endpointInstanceId)
                .setEndpointPeerId(identity.peerId())
                .setEndpointPublicKey(identity.publicKeyBase64())
                .setPublisherId(challenge.getPublisherId())
                .setPublisherPeerId(challenge.getPublisherPeerId())
                .setRegion(challenge.getRegion())
                .addAllAddrs(recordAddrs)
                .addAllCapabilities(capabilities)
                .setCapacity(capacity())
                .setOfflineMode(offlineMode)
                .setSequence(sequence)
                .setIssuedAtUnixMs(nowUnixMs)
                .setRenewedAtUnixMs(nowUnixMs)
                .setExpiresAtUnixMs(nowUnixMs + ttlMs)
                .setNonce(challenge.getNonce())
                .build();
        return PeerRegisterCommit.newBuilder()
                .setRecord(record)
                .setSignature(ByteString.copyFrom(identity.sign(PeerRecordSigningPayload.bytes(record))))
                .build();
    }

    private PeerCapacity capacity() {
        return Objects.requireNonNull(capacitySupplier.get(), "capacity");
    }

    private List<String> challengedRelayCircuitAddrs(PeerRegisterChallenge challenge, List<String> reservedAddrs) {
        List<String> relayAddrs = challenge.getRelayAddrsList();
        Set<String> reservedRelayPeers = relayPeers(reservedAddrs);
        List<String> out = new ArrayList<>(relayAddrs.size());
        for (String relayAddr : relayAddrs) {
            String relayPeer = relayPeer(relayAddr);
            if (relayPeer == null || !reservedRelayPeers.contains(relayPeer)) {
                continue;
            }
            String separator = relayAddr.endsWith("/") ? "" : "/";
            out.add(relayAddr + separator + "p2p-circuit/p2p/" + identity.peerId());
        }
        return out;
    }

    private static Set<String> relayPeers(List<String> addrs) {
        Set<String> peers = new HashSet<>();
        for (String addr : addrs) {
            String peer = relayPeer(addr);
            if (peer != null) {
                peers.add(peer);
            }
        }
        return peers;
    }

    private static String relayPeer(String addr) {
        try {
            String relayAddr = addr.split("/p2p-circuit", 2)[0];
            return Multiaddr.fromString(relayAddr).getPeerId().toBase58();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
