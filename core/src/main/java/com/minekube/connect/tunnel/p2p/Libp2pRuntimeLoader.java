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

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class Libp2pRuntimeLoader {
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

    private static volatile ClassLoader classLoader;

    private Libp2pRuntimeLoader() {
    }

    static ClassLoader classLoader() {
        ClassLoader existing = classLoader;
        if (existing != null) {
            return existing;
        }
        synchronized (Libp2pRuntimeLoader.class) {
            existing = classLoader;
            if (existing == null) {
                existing = new ChildFirstRuntimeClassLoader(runtimeUrls(), Libp2pRuntimeLoader.class.getClassLoader());
                classLoader = existing;
            }
            return existing;
        }
    }

    private static URL[] runtimeUrls() {
        Set<URL> urls = new LinkedHashSet<>();
        CodeSource codeSource = Libp2pRuntimeLoader.class.getProtectionDomain().getCodeSource();
        if (codeSource != null && codeSource.getLocation() != null) {
            urls.add(codeSource.getLocation());
        }
        ClassLoader parent = Libp2pRuntimeLoader.class.getClassLoader();
        if (parent instanceof URLClassLoader) {
            urls.addAll(Arrays.asList(((URLClassLoader) parent).getURLs()));
        } else {
            urls.addAll(classPathUrls());
        }
        return urls.toArray(new URL[0]);
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
