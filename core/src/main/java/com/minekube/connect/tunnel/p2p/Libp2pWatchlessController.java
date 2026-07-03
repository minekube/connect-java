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

import java.util.Objects;

final class Libp2pWatchlessController {
    private static final Runnable NOOP = () -> {
    };

    private final boolean supported;
    private final int statusFailureThreshold;
    private final Runnable readyCallback;
    private final Runnable fallbackCallback;

    private boolean ready;
    private boolean registrationActive;
    private int statusFailures;

    Libp2pWatchlessController(
            boolean supported,
            int statusFailureThreshold,
            Runnable readyCallback,
            Runnable fallbackCallback) {
        this.supported = supported;
        this.statusFailureThreshold = Math.max(1, statusFailureThreshold);
        this.readyCallback = readyCallback == null ? NOOP : Objects.requireNonNull(readyCallback, "readyCallback");
        this.fallbackCallback = fallbackCallback == null ? NOOP : Objects.requireNonNull(fallbackCallback, "fallbackCallback");
    }

    synchronized void registrationCommitted(boolean statusHealthy) {
        if (!supported) {
            return;
        }
        registrationActive = true;
        statusReportResult(statusHealthy);
    }

    synchronized void statusReportResult(boolean healthy) {
        if (!supported || !registrationActive) {
            return;
        }
        if (healthy) {
            statusFailures = 0;
            if (!ready) {
                ready = true;
                readyCallback.run();
            }
            return;
        }
        statusFailures++;
        if (ready && statusFailures >= statusFailureThreshold) {
            ready = false;
            fallbackCallback.run();
        }
    }

    synchronized void registrationClosed() {
        if (!supported) {
            return;
        }
        registrationActive = false;
        statusFailures = 0;
        if (!ready) {
            return;
        }
        ready = false;
        fallbackCallback.run();
    }
}
