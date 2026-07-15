package com.minekube.connect.listener;

import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.bedrock.BedrockIdentityProfiles;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerifier;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.GameProfile.Property;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class VelocityGameProfiles {
    private VelocityGameProfiles() {
    }

    static GameProfile fromConnectPlayer(GameProfile base, ConnectPlayer player) {
        List<Property> properties = Stream.concat(
                        base.getProperties().stream()
                                .filter(property -> !BedrockIdentityVerifier.PROPERTY_NAME.equals(property.getName())),
                        BedrockIdentityProfiles.withoutEnvelope(player.getGameProfile()).getProperties().stream()
                                .map(property -> new Property(
                                        property.getName(),
                                        property.getValue(),
                                        property.getSignature())))
                .collect(Collectors.toList());
        return base.withId(player.getUniqueId())
                .withName(player.getUsername())
                .withProperties(properties);
    }
}
