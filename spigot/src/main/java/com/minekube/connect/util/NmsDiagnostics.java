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
 *
 * @author Minekube
 * @link https://github.com/minekube/connect-java
 */

package com.minekube.connect.util;

import java.lang.reflect.Method;
import java.util.Arrays;
import javax.annotation.Nullable;
import org.bukkit.Bukkit;

/**
 * Describes the running server and latches {@link ClassNames} initialization failures.
 *
 * <p>{@code ClassNames} resolves the server's internals reflectively from a static initializer.
 * When a Minecraft release renames or removes one of those accessors the initializer throws, and
 * from then on the JVM answers every later touch of {@code ClassNames} with
 * {@code NoClassDefFoundError: Could not initialize class ...ClassNames}, whose own message names
 * only the class — the reason that names the drifted accessor is, at best, buried in a cause
 * chain, and the enable path is not guaranteed to hit the informative first touch.
 *
 * <p>This class deliberately lives outside {@code ClassNames} so it still initializes fine once
 * {@code ClassNames} is in the erroneous state. That is what lets
 * {@link #initializationFailure()} hand the original, named reason straight back to the enable
 * path, with no cause-chain digging.
 *
 * <p>Nothing here may throw: it only ever runs while Connect is already reporting a failure.
 */
public final class NmsDiagnostics {
    private static final String UNKNOWN = "unknown";

    private static volatile String initializationFailure;

    private NmsDiagnostics() {
    }

    /**
     * Builds the failure for a server internal Connect knows about but could not resolve.
     *
     * @param accessor the exact accessor that is missing, e.g.
     *                 {@code ServerLoginPacketListenerImpl#startClientVerification(GameProfile)}
     * @param detail   optional extra context, e.g. the names that were tried
     */
    public static IllegalStateException missingAccessor(
            @Nullable String accessor,
            @Nullable String detail) {
        StringBuilder message = new StringBuilder()
                .append("Connect could not resolve the server internal '")
                .append(accessor == null ? UNKNOWN : accessor)
                .append("' on this server.");
        if (detail != null && !detail.isEmpty()) {
            message.append(' ').append(detail);
        }
        message.append(" Connect binds to server internals reflectively, so this means the")
                .append(" server's mappings differ from every name Connect knows — a Minecraft")
                .append(" version/mapping change, not a Java runtime incompatibility.")
                .append(" Environment: ").append(environment()).append('.')
                .append(" Please report this exact line at")
                .append(" https://github.com/minekube/connect-java/issues");
        return new IllegalStateException(message.toString());
    }

    /**
     * Builds the failure for a server internal class Connect could not find under any known name.
     */
    public static IllegalStateException missingClass(
            @Nullable String what,
            String... candidates) {
        String detail = candidates.length == 0
                ? null
                : "Tried: " + String.join(", ", Arrays.asList(candidates)) + ".";
        return missingAccessor(what, detail);
    }

    /**
     * Records why {@code ClassNames} failed to initialize, so the reason survives the
     * causeless {@code NoClassDefFoundError}s the JVM raises on every subsequent touch.
     */
    public static void recordInitializationFailure(@Nullable Throwable failure) {
        if (failure == null) {
            return;
        }
        String message = failure.getMessage();
        if (message == null || message.isEmpty()) {
            message = failure.getClass().getName();
        }
        // Keep the first failure: it is the one that names the accessor that actually drifted.
        if (initializationFailure == null) {
            initializationFailure = message;
        }
    }

    /**
     * The recorded {@code ClassNames} initialization failure, or {@code null} if it initialized
     * fine. Callers use this to report something actionable instead of a causeless
     * {@code NoClassDefFoundError}.
     */
    @Nullable
    public static String initializationFailure() {
        return initializationFailure;
    }

    /** Test seam: resets the latch so a simulated failure does not leak between tests. */
    static void resetInitializationFailure() {
        initializationFailure = null;
    }

    /**
     * A single-line description of the server software and runtime, safe to log. Every lookup
     * degrades to {@code unknown} rather than throwing, because this runs on servers whose API
     * shape is by definition not what Connect expected.
     */
    public static String environment() {
        return "server=" + serverName()
                + ", serverVersion=" + serverVersion()
                + ", bukkitVersion=" + bukkitVersion()
                + ", minecraftVersion=" + minecraftVersion()
                + ", craftbukkitPackage=" + craftBukkitPackage()
                + ", java=" + javaVersion();
    }

    private static String serverName() {
        try {
            return orUnknown(Bukkit.getName());
        } catch (Throwable ignored) {
            return UNKNOWN;
        }
    }

    private static String serverVersion() {
        try {
            return orUnknown(Bukkit.getVersion());
        } catch (Throwable ignored) {
            return UNKNOWN;
        }
    }

    private static String bukkitVersion() {
        try {
            return orUnknown(Bukkit.getBukkitVersion());
        } catch (Throwable ignored) {
            return UNKNOWN;
        }
    }

    /**
     * The Minecraft version. Prefers {@code Server#getMinecraftVersion()} (Paper) and otherwise
     * derives it from the Bukkit version, which is shaped like {@code 1.21.4-R0.1-SNAPSHOT}.
     */
    private static String minecraftVersion() {
        try {
            Object server = Bukkit.getServer();
            if (server != null) {
                // Paper-only accessor, resolved reflectively so this compiles and runs on Spigot.
                Method getMinecraftVersion = ReflectionUtils.getMethod(
                        server.getClass(), "getMinecraftVersion");
                if (getMinecraftVersion != null) {
                    Object version = ReflectionUtils.invoke(server, getMinecraftVersion);
                    if (version instanceof String && !((String) version).isEmpty()) {
                        return (String) version;
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall through to the Bukkit version
        }
        try {
            String bukkitVersion = Bukkit.getBukkitVersion();
            if (bukkitVersion != null && !bukkitVersion.isEmpty()) {
                int dash = bukkitVersion.indexOf('-');
                return dash > 0 ? bukkitVersion.substring(0, dash) : bukkitVersion;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return UNKNOWN;
    }

    /**
     * The CraftBukkit package, which reveals the mapping era: a relocated
     * {@code org.bukkit.craftbukkit.v1_8_R3} on older servers versus a bare
     * {@code org.bukkit.craftbukkit} on Paper 1.20.5+.
     */
    private static String craftBukkitPackage() {
        try {
            Object server = Bukkit.getServer();
            if (server == null) {
                return UNKNOWN;
            }
            Package pkg = server.getClass().getPackage();
            return pkg == null ? UNKNOWN : orUnknown(pkg.getName());
        } catch (Throwable ignored) {
            return UNKNOWN;
        }
    }

    private static String javaVersion() {
        try {
            String version = orUnknown(System.getProperty("java.version"));
            String vendor = System.getProperty("java.vendor");
            return vendor == null || vendor.isEmpty() ? version : version + " (" + vendor + ")";
        } catch (Throwable ignored) {
            return UNKNOWN;
        }
    }

    private static String orUnknown(@Nullable String value) {
        return value == null || value.isEmpty() ? UNKNOWN : value;
    }
}
