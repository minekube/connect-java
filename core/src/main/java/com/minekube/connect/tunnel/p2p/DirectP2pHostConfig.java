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

import java.util.Objects;

public final class DirectP2pHostConfig {
    private final String shareId;
    private final String capability;
    private final String displayName;
    private final boolean internetDirectEnabled;

    public DirectP2pHostConfig(
            String shareId,
            String capability,
            String displayName,
            boolean internetDirectEnabled) {
        this.shareId = requireText(shareId, "shareId");
        this.capability = requireText(capability, "capability");
        this.displayName = requireText(displayName, "displayName");
        this.internetDirectEnabled = internetDirectEnabled;
    }

    public String shareId() {
        return shareId;
    }

    public String capability() {
        return capability;
    }

    public String displayName() {
        return displayName;
    }

    public boolean internetDirectEnabled() {
        return internetDirectEnabled;
    }

    @Override
    public String toString() {
        return "DirectP2pHostConfig{shareId='" + shareId
                + "', capability=<redacted>, displayName='" + displayName
                + "', internetDirectEnabled=" + internetDirectEnabled + "}";
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
