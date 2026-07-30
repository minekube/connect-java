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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

final class MdnsAddressSelector {
    private MdnsAddressSelector() {
    }

    static InetAddress systemAddress() {
        List<Candidate> candidates = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    candidates.add(new Candidate(
                            addresses.nextElement(),
                            network.isUp(),
                            network.supportsMulticast(),
                            network.isLoopback(),
                            network.isPointToPoint(),
                            network.isVirtual(),
                            network.getIndex()));
                }
            }
        } catch (SocketException e) {
            throw new IllegalStateException("Could not select an mDNS network interface", e);
        }
        return select(candidates);
    }

    static InetAddress select(List<Candidate> candidates) {
        return candidates.stream()
                .filter(MdnsAddressSelector::usable)
                .min(Comparator
                        .comparingInt((Candidate candidate) -> scopeRank(candidate.address()))
                        .thenComparing(Candidate::virtual)
                        .thenComparingInt(Candidate::interfaceIndex))
                .map(Candidate::address)
                .orElse(null);
    }

    private static boolean usable(Candidate candidate) {
        InetAddress address = candidate.address();
        return candidate.up()
                && candidate.multicast()
                && !candidate.loopback()
                && !candidate.pointToPoint()
                && address instanceof Inet4Address
                && !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isMulticastAddress();
    }

    private static int scopeRank(InetAddress address) {
        if (address.isSiteLocalAddress()) {
            return 0;
        }
        if (address.isLinkLocalAddress()) {
            return 1;
        }
        return 2;
    }

    static final class Candidate {
        private final InetAddress address;
        private final boolean up;
        private final boolean multicast;
        private final boolean loopback;
        private final boolean pointToPoint;
        private final boolean virtual;
        private final int interfaceIndex;

        Candidate(
                InetAddress address,
                boolean up,
                boolean multicast,
                boolean loopback,
                boolean pointToPoint,
                boolean virtual,
                int interfaceIndex) {
            this.address = Objects.requireNonNull(address, "address");
            this.up = up;
            this.multicast = multicast;
            this.loopback = loopback;
            this.pointToPoint = pointToPoint;
            this.virtual = virtual;
            this.interfaceIndex = interfaceIndex;
        }

        InetAddress address() {
            return address;
        }

        boolean up() {
            return up;
        }

        boolean multicast() {
            return multicast;
        }

        boolean loopback() {
            return loopback;
        }

        boolean pointToPoint() {
            return pointToPoint;
        }

        boolean virtual() {
            return virtual;
        }

        int interfaceIndex() {
            return interfaceIndex;
        }
    }
}
