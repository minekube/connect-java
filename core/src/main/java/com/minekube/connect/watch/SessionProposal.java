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

package com.minekube.connect.watch;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.Getter;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;

public class SessionProposal {
    private static final Gson GSON = new Gson();
    private static final String SCOPE_PROPERTY_NAME = "minekube:bedrock_identity_scope";

    @Getter
    private final Session session;
    private final Consumer<com.google.rpc.Status> reject;
    @Getter
    private final String endpointId;
    @Getter
    private final String endpointOrgId;

    private final AtomicReference<State> state = new AtomicReference<>(State.ACCEPTED);

    public SessionProposal(Session session, Consumer<com.google.rpc.Status> reject) {
        this(session, reject, "", "");
    }

    public SessionProposal(
            Session session,
            Consumer<com.google.rpc.Status> reject,
            String endpointId,
            String endpointOrgId) {
        this.session = session;
        this.reject = reject;
        Scope scope = parseScope(session);
        this.endpointId = firstNonEmpty(endpointId, scope.endpoint_id);
        this.endpointOrgId = firstNonEmpty(endpointOrgId, scope.endpoint_org_id);
    }

    public enum State {
        ACCEPTED,
        REJECTED
    }

    public void reject(com.google.rpc.Status reason) {
        if (state.compareAndSet(State.ACCEPTED, State.REJECTED)) {
            reject.accept(reason);
        }
    }

    public State getState() {
        return state.get();
    }

    @Override
    public String toString() {
        return "SessionProposal{" +
                "session=" + session +
                '}';
    }

    private static Scope parseScope(Session session) {
        if (session == null ||
                !session.hasPlayer() ||
                !session.getPlayer().hasProfile()) {
            return new Scope();
        }
        for (var property : session.getPlayer().getProfile().getPropertiesList()) {
            if (SCOPE_PROPERTY_NAME.equals(property.getName())) {
                try {
                    Scope scope = GSON.fromJson(property.getValue(), Scope.class);
                    return scope == null ? new Scope() : scope;
                } catch (JsonSyntaxException ignored) {
                    return new Scope();
                }
            }
        }
        return new Scope();
    }

    private static String firstNonEmpty(String preferred, String fallback) {
        if (preferred != null && !preferred.isEmpty()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }

    private static final class Scope {
        String endpoint_id = "";
        String endpoint_org_id = "";
    }
}
