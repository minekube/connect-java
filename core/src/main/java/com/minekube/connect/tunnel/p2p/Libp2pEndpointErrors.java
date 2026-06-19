package com.minekube.connect.tunnel.p2p;

import java.util.concurrent.TimeoutException;

final class Libp2pEndpointErrors {
    private Libp2pEndpointErrors() {
    }

    static boolean isTransientConnectError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException) {
                return true;
            }
            String name = current.getClass().getName();
            String message = current.getMessage();
            if (name.contains("ConnectionClosedException")
                    || name.contains("InvalidRemotePubKey")
                    || name.contains("NonCompleteException")
                    || contains(message, "ConnectionClosedException")
                    || contains(message, "InvalidRemotePubKey")
                    || contains(message, "Channel closed")
                    || contains(message, "connection reset")) {
                return true;
            }
        }
        return false;
    }

    static boolean isEdgePeerMismatch(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String name = current.getClass().getName();
            String message = current.getMessage();
            if (name.contains("InvalidRemotePubKey") || contains(message, "InvalidRemotePubKey")) {
                return true;
            }
        }
        return false;
    }

    static String summary(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        Throwable leaf = error;
        while (leaf.getCause() != null) {
            leaf = leaf.getCause();
        }
        String message = leaf.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getMessage();
        }
        return leaf.getClass().getSimpleName() + ": " + (message == null ? "unknown" : message);
    }

    private static boolean contains(String text, String needle) {
        return text != null && text.contains(needle);
    }
}
