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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.craftbukkit.v1_21_R1.VersionedServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the diagnosability of Connect's server-internals ({@code ClassNames}) reflection.
 *
 * <p>Connect binds to server internals reflectively from a static initializer. When a Minecraft
 * release renames or removes one of those accessors, the initializer throws — and before this was
 * hardened the operator got a bare {@code NullPointerException("... cannot be null")} on the first
 * touch and {@code NoClassDefFoundError: Could not initialize class ...ClassNames} on every touch
 * after that. Neither named the server software or the Minecraft version, and only the first named
 * the accessor at all, which is what made a real report impossible to act on.
 *
 * <p>These tests fail against that pre-fix behaviour.
 */
class NmsDiagnosticsTest {
    private static final String SERVER_NAME = "Paper";
    private static final String MINECRAFT_VERSION = "1.21.4";
    private static final String BUKKIT_VERSION = MINECRAFT_VERSION + "-R0.1-SNAPSHOT";
    private static final String SERVER_VERSION = "git-Paper-196 (MC: " + MINECRAFT_VERSION + ")";

    @BeforeAll
    static void installServer() {
        if (Bukkit.getServer() != null) {
            return;
        }
        Server server = mock(Server.class);
        lenient().when(server.getName()).thenReturn(SERVER_NAME);
        lenient().when(server.getVersion()).thenReturn(SERVER_VERSION);
        lenient().when(server.getBukkitVersion()).thenReturn(BUKKIT_VERSION);
        // Bukkit.setServer logs its banner through the server's logger.
        lenient().when(server.getLogger()).thenReturn(Logger.getLogger(SERVER_NAME));
        Bukkit.setServer(server);
    }

    @BeforeEach
    void resetLatch() {
        NmsDiagnostics.resetInitializationFailure();
    }

    @Test
    void missingAccessorNamesTheAccessorAndTheServerItWasLookedUpOn() {
        IllegalStateException failure = NmsDiagnostics.missingAccessor(
                "ServerLoginPacketListenerImpl#startClientVerification(GameProfile)", null);

        String message = failure.getMessage();
        assertNotNull(message);
        // The exact accessor that drifted - the whole point of the report.
        assertTrue(message.contains("startClientVerification"),
                "must name the missing accessor: " + message);
        // Server software and Minecraft version, so the drift can be pinned to a release.
        assertTrue(message.contains(SERVER_NAME), "must name the server software: " + message);
        assertTrue(message.contains(MINECRAFT_VERSION),
                "must name the Minecraft version: " + message);
        assertTrue(message.contains(BUKKIT_VERSION),
                "must name the Bukkit version: " + message);
        // The Java version is reported too, so a Java-runtime theory can be ruled out on sight
        // instead of being guessed at.
        assertTrue(message.contains("java="), "must report the Java version: " + message);
        assertTrue(message.contains("not a Java runtime incompatibility"),
                "must steer away from a Java-runtime misdiagnosis: " + message);
    }

    @Test
    void missingClassListsEveryNameThatWasTried() {
        IllegalStateException failure = NmsDiagnostics.missingClass(
                "LoginListener/ServerLoginPacketListenerImpl",
                "net.minecraft.server.network.ServerLoginPacketListenerImpl",
                "net.minecraft.server.network.LoginListener");

        String message = failure.getMessage();
        assertTrue(message.contains("ServerLoginPacketListenerImpl"),
                "must list the Mojang-mapped candidate: " + message);
        assertTrue(message.contains("net.minecraft.server.network.LoginListener"),
                "must list the fallback candidate: " + message);
    }

    @Test
    void environmentDegradesInsteadOfThrowingWhenLookupsFail() {
        // Runs on servers whose API shape is by definition not what Connect expected, so it must
        // never throw on top of the failure it is describing.
        String environment = NmsDiagnostics.environment();
        assertNotNull(environment);
        assertTrue(environment.contains("server="), environment);
        assertTrue(environment.contains("java="), environment);
    }

    @Test
    void latchKeepsTheFirstFailureRatherThanALaterLessInformativeOne() {
        NmsDiagnostics.recordInitializationFailure(
                new IllegalStateException("names the drifted accessor"));
        NmsDiagnostics.recordInitializationFailure(
                new NoClassDefFoundError("com/minekube/connect/util/ClassNames"));

        assertEquals("names the drifted accessor", NmsDiagnostics.initializationFailure());
    }

    @Test
    void noFailureIsReportedWhenNothingWentWrong() {
        assertNull(NmsDiagnostics.initializationFailure());
    }

    /**
     * The end-to-end shape of the bug: a real {@code ClassNames} static-init failure must stay
     * readable after the JVM has started answering with uninformative top-level
     * {@code NoClassDefFoundError}s.
     *
     * <p>{@code ClassNames} is loaded in a throwaway child-first loader so its initializer runs
     * for real. No {@code net.minecraft.*} exists on the test classpath, so it fails exactly the
     * way a drifted mapping fails on a live server.
     */
    @Test
    void classNamesInitFailureStaysReadableAfterTheUninformativeNoClassDefFoundError()
            throws Exception {
        try (ChildFirstLoader loader = new ChildFirstLoader()) {
            // First touch: the JVM still carries the reason.
            ExceptionInInitializerError first = assertThrows(
                    ExceptionInInitializerError.class,
                    () -> Class.forName(ClassNames.class.getName(), true, loader));
            assertNotNull(first.getCause(), "first touch should still carry a cause");

            // Every later touch degrades to "Could not initialize class ...ClassNames": the
            // top-level message names only the class, never the accessor that actually drifted.
            // That is the burial being pinned - the reason is at best buried in a cause chain.
            NoClassDefFoundError later = assertThrows(
                    NoClassDefFoundError.class,
                    () -> Class.forName(ClassNames.class.getName(), true, loader));
            assertNotNull(later.getMessage());
            assertTrue(!later.getMessage().contains("MinecraftServer"),
                    "the re-initialization error alone never names the drifted accessor: "
                            + later.getMessage());

            // The latch must still hand back the original, actionable reason.
            Class<?> diagnostics = Class.forName(
                    NmsDiagnostics.class.getName(), true, loader);
            Method initializationFailure =
                    diagnostics.getDeclaredMethod("initializationFailure");
            Object recorded = initializationFailure.invoke(null);

            assertNotNull(recorded,
                    "the reason ClassNames failed to initialize must survive the burial");
            String message = (String) recorded;
            assertTrue(message.contains("MinecraftServer"),
                    "must name the server internal that could not be resolved: " + message);
            assertTrue(message.contains(SERVER_NAME),
                    "must name the server software: " + message);
            assertTrue(message.contains(MINECRAFT_VERSION),
                    "must name the Minecraft version: " + message);
        }
    }

    @Test
    void fallbackClassFailureNamesEveryCandidateAndTheEnvironment() throws Exception {
        Server previousServer = Bukkit.getServer();
        VersionedServer versionedServer = mock(VersionedServer.class);
        when(versionedServer.getName()).thenReturn(SERVER_NAME);
        when(versionedServer.getVersion()).thenReturn(SERVER_VERSION);
        when(versionedServer.getBukkitVersion()).thenReturn(BUKKIT_VERSION);
        when(versionedServer.getLogger()).thenReturn(Logger.getLogger(SERVER_NAME));
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, versionedServer);
        try (ChildFirstLoader loader = new ChildFirstLoader()) {
            assertThrows(
                    ExceptionInInitializerError.class,
                    () -> Class.forName(ClassNames.class.getName(), true, loader));

            Class<?> diagnostics = Class.forName(
                    NmsDiagnostics.class.getName(), true, loader);
            Method initializationFailure = diagnostics.getDeclaredMethod("initializationFailure");
            String message = (String) initializationFailure.invoke(null);

            assertNotNull(message);
            assertTrue(message.contains("net.minecraft.server.MinecraftServer"), message);
            assertTrue(message.contains("net.minecraft.server.v1_21_R1.MinecraftServer"), message);
            assertTrue(message.contains(SERVER_NAME), message);
            assertTrue(message.contains(BUKKIT_VERSION), message);
            assertTrue(message.contains(MINECRAFT_VERSION), message);
            assertTrue(message.contains("java="), message);
        } finally {
            serverField.set(null, previousServer);
        }
    }

    /**
     * Loads Connect's util classes child-first so {@code ClassNames} gets a fresh, uninitialized
     * copy per test, while {@code org.bukkit.*} stays parent-loaded so the mock server installed
     * above is the one it sees.
     */
    private static final class ChildFirstLoader extends URLClassLoader {
        private static final String CHILD_FIRST_PREFIX = "com.minekube.connect.util.";

        ChildFirstLoader() {
            super(classPathUrls(), NmsDiagnosticsTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && name.startsWith(CHILD_FIRST_PREFIX)) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        loaded = null;
                    }
                }
                if (loaded == null) {
                    loaded = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private static URL[] classPathUrls() {
            List<URL> urls = new ArrayList<>();
            for (String entry : System.getProperty("java.class.path", "")
                    .split(File.pathSeparator)) {
                if (entry.isEmpty()) {
                    continue;
                }
                try {
                    urls.add(new File(entry).toURI().toURL());
                } catch (MalformedURLException ignored) {
                    // Skip unusable entries; the parent loader remains the fallback.
                }
            }
            return urls.toArray(new URL[0]);
        }
    }
}
