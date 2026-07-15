package com.minekube.connect.api.player.bedrock;

import com.minekube.connect.api.player.GameProfile;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Utilities for removing private Bedrock identity admission properties from public profiles. */
public final class BedrockIdentityProfiles {
    /** Private transport property carrying endpoint and organization scope for legacy Watch. */
    public static final String SCOPE_PROPERTY_NAME = "minekube:bedrock_identity_scope";

    private BedrockIdentityProfiles() {
    }

    /**
     * Returns a profile without the signed identity envelope or transport scope property.
     *
     * @param profile the profile to sanitize
     * @return {@code profile} when it contains no private properties, otherwise a sanitized copy
     */
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

    /**
     * Determines whether a profile property may be exposed outside identity admission.
     *
     * @param property the property to classify
     * @return true unless the property is reserved for Bedrock identity admission
     */
    public static boolean isPublic(GameProfile.Property property) {
        return !BedrockIdentityVerifier.PROPERTY_NAME.equals(property.getName()) &&
                !SCOPE_PROPERTY_NAME.equals(property.getName());
    }
}
