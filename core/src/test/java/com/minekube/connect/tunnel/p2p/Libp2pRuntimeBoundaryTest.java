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

package com.minekube.connect.tunnel.p2p;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Libp2pRuntimeBoundaryTest {
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "io.libp2p.",
            "io.netty.",
            "com.google.protobuf.",
            "kotlin.");

    @Test
    void parentFacingEndpointDoesNotHoldIsolatedRuntimeTypes() {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : List.of(Libp2pEndpoint.class, Libp2pRuntime.class, Libp2pTunnelTransport.class)) {
            inspectFields(type, violations);
            inspectMethods(type, violations);
            inspectConstructors(type, violations);
        }
        assertTrue(violations.isEmpty(), () -> "Forbidden parent-facing libp2p runtime types:\n"
                + String.join("\n", violations));
    }

    private static void inspectFields(Class<?> owner, List<String> violations) {
        for (Field field : owner.getDeclaredFields()) {
            if (isForbidden(field.getType())) {
                violations.add(owner.getName() + "#" + field.getName() + ": " + field.getType().getName());
            }
        }
    }

    private static void inspectMethods(Class<?> owner, List<String> violations) {
        for (Method method : owner.getDeclaredMethods()) {
            if (isForbidden(method.getReturnType())) {
                violations.add(owner.getName() + "#" + method.getName() + " returns "
                        + method.getReturnType().getName());
            }
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (isForbidden(parameterType)) {
                    violations.add(owner.getName() + "#" + method.getName() + " parameter "
                            + parameterType.getName());
                }
            }
        }
    }

    private static void inspectConstructors(Class<?> owner, List<String> violations) {
        for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                if (isForbidden(parameterType)) {
                    violations.add(owner.getName() + " constructor parameter " + parameterType.getName());
                }
            }
        }
    }

    private static boolean isForbidden(Class<?> type) {
        if (type.isArray()) {
            return isForbidden(type.getComponentType());
        }
        String name = type.getName();
        for (String prefix : FORBIDDEN_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
