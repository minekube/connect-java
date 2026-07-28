package com.minekube.connect.listener;

import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.bedrock.BedrockIdentityProfiles;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerifier;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.GameProfile.Property;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class VelocityGameProfiles {
    private VelocityGameProfiles() {
    }

    /**
     * The profile Connect wants this connection to have: Connect's identity and properties, plus
     * whatever else the base profile carried.
     *
     * <p>Idempotent on purpose. {@link VelocityLateReassertListener} applies this a second time,
     * after every other plugin, on a base that already went through it once - Connect's own
     * properties must replace their earlier copies rather than be appended to them.
     */
    static GameProfile fromConnectPlayer(GameProfile base, ConnectPlayer player) {
        List<Property> connectProperties =
                BedrockIdentityProfiles.withoutEnvelope(player.getGameProfile()).getProperties()
                        .stream()
                        .map(property -> new Property(
                                property.getName(),
                                property.getValue(),
                                property.getSignature()))
                        .collect(Collectors.toList());
        Set<String> connectPropertyNames = connectProperties.stream()
                .map(Property::getName)
                .collect(Collectors.toSet());

        List<Property> properties = Stream.concat(
                        base.getProperties().stream()
                                .filter(property -> !isPrivateIdentity(property))
                                .filter(property -> !connectPropertyNames.contains(property.getName())),
                        connectProperties.stream())
                .collect(Collectors.toList());
        return base.withId(player.getUniqueId())
                .withName(player.getUsername())
                .withProperties(properties);
    }

    private static boolean isPrivateIdentity(Property property) {
        return BedrockIdentityVerifier.PROPERTY_NAME.equals(property.getName()) ||
                BedrockIdentityProfiles.SCOPE_PROPERTY_NAME.equals(property.getName());
    }
}
