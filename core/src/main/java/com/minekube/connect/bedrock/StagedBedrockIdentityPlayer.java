package com.minekube.connect.bedrock;

import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.player.ConnectPlayerImpl;
import java.util.Objects;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionProtocol;

final class StagedBedrockIdentityPlayer extends ConnectPlayerImpl {
    private final VerifiedBedrockIdentityRegistry identityRegistry;

    StagedBedrockIdentityPlayer(
            ConnectPlayer player,
            VerifiedBedrockIdentityRegistry identityRegistry) {
        super(player.getSessionId(), player.getGameProfile(), player.getAuth(), player.getLanguageTag());
        this.identityRegistry = Objects.requireNonNull(identityRegistry, "identityRegistry");
    }

    BedrockIdentityEnforcer.Decision verifyAdmission(
            BedrockIdentityEnforcer enforcer,
            String endpointId,
            String endpointOrgId,
            SessionProtocol protocol) {
        GameProfile profile = identityRegistry.takeAdmissionProfile(this).orElse(getGameProfile());
        identityRegistry.clearClaims(this);
        BedrockIdentityEnforcer.Decision decision = enforcer.verifyAdmissionSnapshot(
                this, profile, endpointId, endpointOrgId, protocol);
        if (decision.verifiedClaims() != null) {
            identityRegistry.record(this, decision.verifiedClaims());
        }
        return decision;
    }
}
