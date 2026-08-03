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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class MdnsAddressSelectorTest {
    @Test
    void prefersPrivatePhysicalMulticastInterface() throws Exception {
        InetAddress selected = MdnsAddressSelector.select(List.of(
                candidate("203.0.113.20", true, true, false, false, false, 8),
                candidate("192.168.178.100", true, true, false, false, false, 14),
                candidate("192.168.64.1", true, true, false, false, true, 21)));

        assertEquals("192.168.178.100", selected.getHostAddress());
    }

    @Test
    void ignoresInterfacesThatCannotCarryLanMulticast() throws Exception {
        InetAddress selected = MdnsAddressSelector.select(List.of(
                candidate("192.168.1.10", false, true, false, false, false, 1),
                candidate("192.168.1.11", true, false, false, false, false, 2),
                candidate("192.168.1.12", true, true, true, false, false, 3),
                candidate("192.168.1.13", true, true, false, true, false, 4),
                candidate("127.0.0.1", true, true, false, false, false, 5),
                candidate("2001:db8::10", true, true, false, false, false, 6)));

        assertNull(selected);
    }

    @Test
    void fallsBackToPublicIpv4WhenItIsTheOnlyUsableInterface() throws Exception {
        InetAddress selected = MdnsAddressSelector.select(List.of(
                candidate("203.0.113.20", true, true, false, false, false, 8)));

        assertEquals("203.0.113.20", selected.getHostAddress());
    }

    private static MdnsAddressSelector.Candidate candidate(
            String address,
            boolean up,
            boolean multicast,
            boolean loopback,
            boolean pointToPoint,
            boolean virtual,
            int index) throws Exception {
        return new MdnsAddressSelector.Candidate(
                InetAddress.getByName(address),
                up,
                multicast,
                loopback,
                pointToPoint,
                virtual,
                index);
    }
}
