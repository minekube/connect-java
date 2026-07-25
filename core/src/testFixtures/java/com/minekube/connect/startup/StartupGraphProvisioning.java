/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
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
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Floodgate
 */

package com.minekube.connect.startup;

import com.minekube.connect.ConnectPlatform;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.bedrock.BedrockIdentityEnforcer;
import com.minekube.connect.bedrock.BedrockIdentityKeyProvider;
import com.minekube.connect.bedrock.VerifiedBedrockIdentityRegistry;
import com.minekube.connect.packet.PacketHandlersImpl;
import com.minekube.connect.register.WatchHealthServer;
import com.minekube.connect.register.WatcherRegister;
import com.minekube.connect.tunnel.Tunneler;
import com.minekube.connect.tunnel.WebSocketTunnelTransport;
import com.minekube.connect.tunnel.p2p.Libp2pEndpoint;
import com.minekube.connect.tunnel.p2p.Libp2pTunnelTransport;
import com.minekube.connect.util.Metrics;
import com.minekube.connect.util.UpdateChecker;
import com.minekube.connect.watch.WatchClient;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reusable startup / DI-provisioning checks shared by the per-platform plugin startup tests
 * ({@code VelocityPluginStartupTest}, {@code SpigotPluginStartupTest},
 * {@code BungeePluginStartupTest}) and the core {@code PluginGraphStartupTest}.
 *
 * <p>It is static and dependency-free (pure JDK reflection) so every platform module can consume it
 * as a {@code testFixtures} artifact without pulling Guice 7 or {@code jakarta.inject} onto its
 * classpath. It has two jobs:
 *
 * <ol>
 *   <li>{@link #reachableInjectedTypes(Collection)} walks the real {@code @Inject} object graph the
 *       plugin provisions from a set of root types, so a new DI class added anywhere on a platform's
 *       graph is covered automatically instead of having to be re-listed by hand.</li>
 *   <li>{@link #guice7ProvisioningViolations(Collection)} replays Guice 7's injectable-constructor
 *       discovery rule (the Guice shipped by Velocity 4.0.0) plus a {@code javax.inject} scan, so a
 *       regression that makes any provider unprovisionable — like the 0.12.x
 *       {@code BedrockIdentityKeyProvider} "Cant create plugin connect" failure — is caught on every
 *       platform's injector, not just Velocity's.</li>
 * </ol>
 *
 * <p><b>Why a Guice 7 replica instead of a real Guice 7 injector.</b> This repo compiles and tests
 * against Guice 6 (see {@code Versions.guiceVersion}), which recognizes BOTH {@code javax.inject}
 * and {@code com.google.inject} injectable-constructor markers. Provisioning the graph through the
 * real Guice-6 injector therefore succeeds even with the {@code javax.inject} annotations that broke
 * Velocity 4.0.0 and cannot reproduce the failure. No Guice 7 / Velocity 4.0.0 harness is on the
 * classpath, so this fixture replicates Guice 7's exact injectable-constructor discovery rule and
 * forbids {@code javax.inject} annotations across the plugin DI graph. The checks fail before the
 * Velocity-4 DI fix and pass after it. This mirrors the reasoning in
 * {@code BedrockVelocityGuice7ProvisioningTest}, generalized from a hand-listed set of classes to
 * the whole per-platform graph.
 */
public final class StartupGraphProvisioning {

    private static final String CONNECT_PACKAGE = "com.minekube.connect";

    /** Injectable-constructor markers that Guice recognizes across versions (used for traversal). */
    private static final Set<String> INJECT_ANNOTATIONS = Set.of(
            "com.google.inject.Inject", "javax.inject.Inject", "jakarta.inject.Inject");

    /**
     * Markers Guice 7 accepts as an injectable-constructor annotation. Referenced by
     * fully-qualified name so this fixture needs neither Guice 7 nor {@code jakarta.inject} on its
     * classpath.
     */
    private static final Set<String> GUICE7_INJECT_ANNOTATIONS = Set.of(
            "com.google.inject.Inject", "jakarta.inject.Inject");

    private static final String JAVAX_INJECT_PREFIX = "javax.inject.";

    private StartupGraphProvisioning() {
    }

    /**
     * Core types Guice constructs while the plugin loads and enables, on every platform. These are
     * the singletons the platform entrypoints materialize through {@code getInstance(...)} /
     * {@code asEagerSingleton()} plus the Bedrock identity graph reached during construction. Used
     * as traversal roots by all per-platform startup tests so the shared graph is exercised
     * identically on each injector.
     */
    public static List<Class<?>> coreRuntimeGraphRoots() {
        return List.of(
                ConnectPlatform.class,
                PacketHandlersImpl.class,
                Libp2pEndpoint.class,
                Libp2pTunnelTransport.class,
                WebSocketTunnelTransport.class,
                Tunneler.class,
                WatcherRegister.class,
                WatchHealthServer.class,
                WatchClient.class,
                UpdateChecker.class,
                Metrics.class,
                BedrockIdentityEnforcer.class,
                BedrockAdmissionCoordinator.class,
                BedrockIdentityKeyProvider.class,
                VerifiedBedrockIdentityRegistry.class);
    }

    /**
     * Transitively walks the {@code @Inject} object graph reachable from {@code roots} and returns
     * every concrete {@code com.minekube.connect.*} class Guice would just-in-time instantiate to
     * satisfy those injection points. Edges followed: the single injectable constructor's
     * parameters, {@code @Inject} fields, and {@code @Inject} method parameters, across each class's
     * hierarchy. Interfaces/abstract types and non-Connect types are traversal boundaries (Guice
     * resolves them through explicit bindings or platform SDK objects, not JIT construction).
     *
     * <p>The returned set is exactly the population that a DI regression could make unprovisionable,
     * so it is what {@link #guice7ProvisioningViolations(Collection)} is run against.
     */
    public static Set<Class<?>> reachableInjectedTypes(Collection<Class<?>> roots) {
        Set<Class<?>> discovered = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> visited = new LinkedHashSet<>();

        // A root that Guice itself constructs (has an injectable constructor) is part of the graph
        // to check; roots created inside @Provides methods (no injectable constructor) are seeds
        // only and are covered through the dependencies they expose.
        for (Class<?> root : roots) {
            if (isConnectConcrete(root) && hasInjectConstructor(root)) {
                discovered.add(root);
            }
            enqueue(root, queue, visited);
        }

        while (!queue.isEmpty()) {
            Class<?> type = queue.poll();
            for (Class<?> dependency : injectedDependencies(type)) {
                if (isConnectConcrete(dependency)) {
                    discovered.add(dependency);
                }
                enqueue(dependency, queue, visited);
            }
        }
        return discovered;
    }

    /**
     * Applies Guice 7's injectable-constructor discovery rule and a {@code javax.inject} scan to
     * each type and returns a human-readable violation for every type Guice 7 could NOT provision
     * (or that leans on {@code javax.inject} annotations Guice 7 ignores). An empty list means the
     * whole graph is provisionable on a Velocity-4-class (Guice 7) injector.
     */
    public static List<String> guice7ProvisioningViolations(Collection<Class<?>> types) {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : types) {
            violations.addAll(guice7ProvisioningViolations(type));
        }
        return violations;
    }

    private static List<String> guice7ProvisioningViolations(Class<?> type) {
        List<String> violations = new ArrayList<>();

        List<Constructor<?>> injectConstructors = new ArrayList<>();
        List<Constructor<?>> guice7InjectConstructors = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (hasAnnotationNamed(constructor, INJECT_ANNOTATIONS)) {
                injectConstructors.add(constructor);
            }
            if (hasAnnotationNamed(constructor, GUICE7_INJECT_ANNOTATIONS)) {
                guice7InjectConstructors.add(constructor);
            }
        }

        // A type with no @Inject constructor is not just-in-time constructed by Guice: it is
        // supplied by an @Provides method / toInstance / linked binding (e.g. ConfigLoader,
        // SimpleConnectApi, BedrockIdentityReadiness are built with `new` inside CommonModule), so
        // Guice never inspects it for an injectable constructor and the javax regression cannot
        // apply. Only the classes Guice itself instantiates via constructor injection are subject to
        // Guice 7's discovery rule — exactly the population the Velocity 4 failure came from.
        if (injectConstructors.isEmpty()) {
            return violations;
        }

        if (guice7InjectConstructors.size() > 1) {
            violations.add(type.getName() + " has more than one Guice 7 @Inject constructor: "
                    + guice7InjectConstructors);
        }

        boolean provisionable =
                guice7InjectConstructors.size() == 1 || hasInjectableNoArgConstructor(type);
        if (!provisionable) {
            violations.add(type.getName() + " has an @Inject constructor that Guice 7 does NOT "
                    + "recognize (it uses only javax.inject, which Guice 7 on Velocity 4.0.0 "
                    + "ignores) and NO no-arg constructor, so Guice 7 cannot provision it — "
                    + "reproducing the \"Cant create plugin connect\" failure.");
        }

        for (String offender : javaxInjectAnnotations(type)) {
            violations.add(type.getName() + " uses a javax.inject annotation invisible to Guice 7 "
                    + "on Velocity 4.0.0: " + offender);
        }
        return violations;
    }

    // --- graph traversal ----------------------------------------------------------------------

    private static void enqueue(Class<?> type, Deque<Class<?>> queue, Set<Class<?>> visited) {
        if (type != null && type.getName().startsWith(CONNECT_PACKAGE) && visited.add(type)) {
            queue.add(type);
        }
    }

    private static Set<Class<?>> injectedDependencies(Class<?> type) {
        Set<Class<?>> dependencies = new LinkedHashSet<>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            try {
                Constructor<?> injectConstructor = injectConstructor(current);
                if (injectConstructor != null) {
                    for (Class<?> parameterType : injectConstructor.getParameterTypes()) {
                        dependencies.add(parameterType);
                    }
                }
                for (Field field : current.getDeclaredFields()) {
                    if (hasAnnotationNamed(field, INJECT_ANNOTATIONS)) {
                        dependencies.add(field.getType());
                    }
                }
                for (Method method : current.getDeclaredMethods()) {
                    if (hasAnnotationNamed(method, INJECT_ANNOTATIONS)) {
                        for (Class<?> parameterType : method.getParameterTypes()) {
                            dependencies.add(parameterType);
                        }
                    }
                }
            } catch (NoClassDefFoundError | RuntimeException ignored) {
                // A dependency type not on this module's test classpath is not part of the Connect
                // DI graph we assert over; skip it defensively rather than fail the walk.
            }
        }
        return dependencies;
    }

    private static Constructor<?> injectConstructor(Class<?> type) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (hasAnnotationNamed(constructor, INJECT_ANNOTATIONS)) {
                return constructor;
            }
        }
        return null;
    }

    private static boolean hasInjectConstructor(Class<?> type) {
        return injectConstructor(type) != null;
    }

    private static boolean isConnectConcrete(Class<?> type) {
        return type.getName().startsWith(CONNECT_PACKAGE)
                && !type.isInterface()
                && !type.isAnnotation()
                && !type.isEnum()
                && !Modifier.isAbstract(type.getModifiers());
    }

    // --- Guice 7 injectable-constructor rule replica ------------------------------------------

    private static boolean hasInjectableNoArgConstructor(Class<?> type) {
        try {
            Constructor<?> noArg = type.getDeclaredConstructor();
            return !Modifier.isPrivate(noArg.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean hasAnnotationNamed(AnnotatedElement element, Set<String> annotationNames) {
        for (Annotation annotation : element.getAnnotations()) {
            if (annotationNames.contains(annotation.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> javaxInjectAnnotations(Class<?> type) {
        List<String> offenders = new ArrayList<>();
        collectJavaxInject(type, offenders); // class-level scope, e.g. @Singleton
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            collectJavaxInject(constructor, offenders);
            for (Parameter parameter : constructor.getParameters()) {
                collectJavaxInject(parameter, offenders);
            }
        }
        for (Field field : type.getDeclaredFields()) {
            collectJavaxInject(field, offenders);
        }
        for (Method method : type.getDeclaredMethods()) {
            collectJavaxInject(method, offenders);
            for (Parameter parameter : method.getParameters()) {
                collectJavaxInject(parameter, offenders);
            }
        }
        return offenders;
    }

    private static void collectJavaxInject(AnnotatedElement element, List<String> offenders) {
        for (Annotation annotation : element.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (name.startsWith(JAVAX_INJECT_PREFIX)) {
                offenders.add(name + " on " + element);
            }
        }
    }
}
