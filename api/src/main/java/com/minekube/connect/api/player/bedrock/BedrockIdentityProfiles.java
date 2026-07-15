package com.minekube.connect.api.player.bedrock;

import com.minekube.connect.api.player.GameProfile;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class BedrockIdentityProfiles {
    private BedrockIdentityProfiles() {
    }

    public static GameProfile withoutEnvelope(GameProfile profile) {
        Objects.requireNonNull(profile, "profile");
        List<GameProfile.Property> properties = profile.getProperties().stream()
                .filter(BedrockIdentityProfiles::isPublic)
                .collect(Collectors.toList());
        if (properties.size() == profile.getProperties().size()) {
            return profile;
        }
        return new GameProfile(
                profile.getUsername(),
                profile.getUniqueId(),
                Collections.unmodifiableList(properties));
    }

    public static boolean isPublic(GameProfile.Property property) {
        return !BedrockIdentityVerifier.PROPERTY_NAME.equals(property.getName());
    }
}
