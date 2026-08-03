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
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.bedrock.BedrockIdentityProfiles;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerifier;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator.AdmissionToken;
import java.util.function.Consumer;
import lombok.Getter;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionProtocol;

public class SessionProposal {
    private static final Gson GSON = new Gson();
    @Getter
    private final Session session;
    @Getter
    private final AdmissionToken admissionToken;
    private final Consumer<com.google.rpc.Status> reject;
    @Getter
    private final String endpointId;
    @Getter
    private final String endpointOrgId;
    @Getter
    private final SessionProtocol protocol;
    private final boolean bedrockPrincipalV2;

    private final java.util.concurrent.atomic.AtomicReference<State> state = new java.util.concurrent.atomic.AtomicReference<>(State.ACCEPTED);

    public SessionProposal(Session session, Consumer<com.google.rpc.Status> reject) {
        this(session, reject, "", "", null);
    }

    public SessionProposal(
            Session session,
            Consumer<com.google.rpc.Status> reject,
            String endpointId,
            String endpointOrgId) {
        this(session, reject, endpointId, endpointOrgId, null);
    }

    public SessionProposal(
            Session session,
            Consumer<com.google.rpc.Status> reject,
            String endpointId,
            String endpointOrgId,
            AdmissionToken admissionToken) {
        this.bedrockPrincipalV2 = session != null && !session.getSignedBedrockPrincipalV2().isEmpty();
        this.session = withoutPrivateIdentity(session);
        this.admissionToken = admissionToken;
        this.reject = reject;
        Scope scope = parseScope(session);
        this.endpointId = firstNonEmpty(endpointId,
                firstNonEmpty(session == null ? "" : session.getEndpointId(), scope.endpoint_id));
        this.endpointOrgId = firstNonEmpty(endpointOrgId,
                firstNonEmpty(session == null ? "" : session.getOrganizationId(), scope.endpoint_org_id));
        this.protocol = session == null
                ? SessionProtocol.SESSION_PROTOCOL_UNSPECIFIED
                : session.getProtocol();
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

    /** Whether the private authenticated proposal carried a v2 principal envelope. */
    public boolean hasBedrockPrincipalV2() {
        return bedrockPrincipalV2;
    }

    @Override
    public String toString() {
        return "SessionProposal[sessionId=" + (session == null ? "" : session.getId())
                + ", protocol=" + protocol + ", bedrockPrincipalV2=" + bedrockPrincipalV2 + ']';
    }

    private static Scope parseScope(Session session) {
        if (session == null ||
                !session.hasPlayer() ||
                !session.getPlayer().hasProfile()) {
            return new Scope();
        }
        for (var property : session.getPlayer().getProfile().getPropertiesList()) {
            if (BedrockIdentityProfiles.SCOPE_PROPERTY_NAME.equals(property.getName())) {
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

    private static Session withoutPrivateIdentity(Session session) {
        if (!hasPrivateIdentity(session)) {
            return session;
        }
        var sanitized = session.toBuilder()
                .clearConnectSessionNonce()
                .clearSignedBedrockPrincipalV2();
        if (session.hasPlayer() && session.getPlayer().hasProfile()) {
            var profile = session.getPlayer().getProfile().toBuilder().clearProperties();
            for (var property : session.getPlayer().getProfile().getPropertiesList()) {
                if (!BedrockIdentityVerifier.PROPERTY_NAME.equals(property.getName()) &&
                        !BedrockIdentityProfiles.SCOPE_PROPERTY_NAME.equals(property.getName()) &&
                        !BedrockIdentityProfiles.PRINCIPAL_V2_PROPERTY_NAME.equals(property.getName())) {
                    profile.addProperties(property);
                }
            }
            sanitized.setPlayer(session.getPlayer().toBuilder().setProfile(profile));
        }
        return sanitized.build();
    }

    private static boolean hasPrivateIdentity(Session session) {
        if (session == null) {
            return false;
        }
        if (!session.getConnectSessionNonce().isEmpty()
                || !session.getSignedBedrockPrincipalV2().isEmpty()) return true;
        if (!session.hasPlayer() || !session.getPlayer().hasProfile()) return false;
        return session.getPlayer().getProfile().getPropertiesList().stream()
                .anyMatch(property ->
                        BedrockIdentityVerifier.PROPERTY_NAME.equals(property.getName()) ||
                                BedrockIdentityProfiles.SCOPE_PROPERTY_NAME.equals(property.getName()) ||
                                BedrockIdentityProfiles.PRINCIPAL_V2_PROPERTY_NAME.equals(property.getName()));
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
