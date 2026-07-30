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

package com.minekube.connect.tunnel.p2p;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class Libp2pRuntimeLoader {
    private static final String RUNTIME_RESOURCE = "META-INF/connect/libp2p-runtime.jar";
    private static final List<String> CHILD_FIRST_PREFIXES = Arrays.asList(
            "com.minekube.connect.tunnel.p2p.",
            "io.libp2p.",
            "io.netty.",
            "kotlin.",
            "kotlinx.");
    private static final Set<String> PARENT_FIRST_CLASSES = new HashSet<>(Arrays.asList(
            "com.minekube.connect.tunnel.p2p.Libp2pEndpoint",
            "com.minekube.connect.tunnel.p2p.Libp2pRuntime",
            "com.minekube.connect.tunnel.p2p.Libp2pRuntimeLoader",
            "com.minekube.connect.tunnel.p2p.Libp2pTunnelTransport"));

    private static volatile ChildFirstRuntimeClassLoader classLoader;
    private static Path runtimePayload;
    private static boolean shutdownHookInstalled;

    private Libp2pRuntimeLoader() {
    }

    static ClassLoader classLoader() {
        ChildFirstRuntimeClassLoader existing = classLoader;
        if (existing != null) {
            return existing;
        }
        synchronized (Libp2pRuntimeLoader.class) {
            existing = classLoader;
            if (existing == null) {
                RuntimeLocation runtime = runtimeLocation();
                existing = new ChildFirstRuntimeClassLoader(
                        runtime.urls,
                        Libp2pRuntimeLoader.class.getClassLoader());
                classLoader = existing;
                runtimePayload = runtime.payload;
                installShutdownHook();
            }
            return existing;
        }
    }

    static void close() {
        ChildFirstRuntimeClassLoader closing;
        Path payload;
        synchronized (Libp2pRuntimeLoader.class) {
            closing = classLoader;
            payload = runtimePayload;
            classLoader = null;
            runtimePayload = null;
        }
        if (closing != null) {
            try {
                closing.close();
            } catch (IOException ignored) {
                // Closing is best effort during platform shutdown.
            }
        }
        if (payload != null) {
            try {
                deleteRuntimePayload(payload);
            } catch (IOException ignored) {
                // The operating system can clear a stale temporary payload later.
            }
        }
    }

    private static RuntimeLocation runtimeLocation() {
        InputStream packaged = Libp2pRuntimeLoader.class
                .getClassLoader()
                .getResourceAsStream(RUNTIME_RESOURCE);
        if (packaged == null) {
            return new RuntimeLocation(developmentRuntimeUrls(), null);
        }
        try (InputStream input = packaged) {
            Path payload = extractRuntimePayload(input);
            Set<URL> urls = new LinkedHashSet<>();
            codeSourceUrl().ifPresent(urls::add);
            urls.add(payload.toUri().toURL());
            return new RuntimeLocation(urls.toArray(new URL[0]), payload);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not extract the isolated Connect libp2p runtime",
                    e);
        }
    }

    private static URL[] developmentRuntimeUrls() {
        Set<URL> urls = new LinkedHashSet<>();
        codeSourceUrl().ifPresent(urls::add);
        ClassLoader parent = Libp2pRuntimeLoader.class.getClassLoader();
        if (parent instanceof URLClassLoader) {
            urls.addAll(Arrays.asList(((URLClassLoader) parent).getURLs()));
        } else {
            urls.addAll(classPathUrls());
        }
        return urls.toArray(new URL[0]);
    }

    static Path extractRuntimePayload(InputStream input) throws IOException {
        Path directory = Files.createTempDirectory("minekube-connect-libp2p-");
        Path partial = directory.resolve("libp2p-runtime.part");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try (DigestInputStream source = new DigestInputStream(input, digest)) {
            Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable failure) {
            Files.deleteIfExists(partial);
            Files.deleteIfExists(directory);
            throw failure;
        }

        String hash = hexadecimal(digest.digest());
        Path target = directory.resolve("libp2p-runtime-" + hash + ".jar");
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(partial, target);
        }
        return target;
    }

    static void deleteRuntimePayload(Path payload) throws IOException {
        Files.deleteIfExists(payload);
        Path directory = payload.getParent();
        if (directory != null) {
            Files.deleteIfExists(directory);
        }
    }

    private static java.util.Optional<URL> codeSourceUrl() {
        CodeSource codeSource = Libp2pRuntimeLoader.class.getProtectionDomain().getCodeSource();
        return codeSource == null
                ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(codeSource.getLocation());
    }

    private static String hexadecimal(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            value.append(String.format("%02x", current & 0xff));
        }
        return value.toString();
    }

    private static synchronized void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(
                new Thread(Libp2pRuntimeLoader::close, "Connect libp2p runtime cleanup"));
        shutdownHookInstalled = true;
    }

    private static List<URL> classPathUrls() {
        List<URL> urls = new ArrayList<>();
        String classPath = System.getProperty("java.class.path", "");
        if (classPath.isEmpty()) {
            return urls;
        }
        for (String entry : classPath.split(java.io.File.pathSeparator)) {
            try {
                urls.add(new java.io.File(entry).toURI().toURL());
            } catch (MalformedURLException ignored) {
                // Ignore malformed classpath entries; the parent classloader remains the fallback.
            }
        }
        return urls;
    }

    private static final class RuntimeLocation {
        private final URL[] urls;
        private final Path payload;

        private RuntimeLocation(URL[] urls, Path payload) {
            this.urls = urls;
            this.payload = payload;
        }
    }

    private static final class ChildFirstRuntimeClassLoader extends URLClassLoader {
        static {
            ClassLoader.registerAsParallelCapable();
        }

        private ChildFirstRuntimeClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && isChildFirst(name)) {
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

        private static boolean isChildFirst(String name) {
            if (PARENT_FIRST_CLASSES.contains(name)) {
                return false;
            }
            for (String prefix : CHILD_FIRST_PREFIXES) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }
}
