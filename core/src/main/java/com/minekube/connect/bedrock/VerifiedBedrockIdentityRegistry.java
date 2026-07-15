package com.minekube.connect.bedrock;

import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.api.player.bedrock.BedrockIdentityClaims;
import com.minekube.connect.api.player.bedrock.BedrockIdentityProfiles;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerifier;
import com.minekube.connect.player.ConnectPlayerImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Singleton;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;

@Singleton
public final class VerifiedBedrockIdentityRegistry {
    private final Map<String, BedrockIdentityClaims> identities = new ConcurrentHashMap<>();
    private final Map<String, GameProfile> admissionProfiles = new ConcurrentHashMap<>();

    public ConnectPlayer stage(Session session) {
        Objects.requireNonNull(session, "session");
        Player player = session.getPlayer();
        GameProfile rawProfile = new GameProfile(
                player.getProfile().getName(),
                java.util.UUID.fromString(player.getProfile().getId()),
                Collections.unmodifiableList(player.getProfile().getPropertiesList().stream()
                        .map(property -> new GameProfile.Property(
                                property.getName(),
                                property.getValue(),
                                property.getSignature()))
                        .collect(java.util.stream.Collectors.toList())));
        ConnectPlayer rawPlayer = new ConnectPlayerImpl(
                session.getId(),
                rawProfile,
                new Auth(session.getAuth().getPassthrough()),
                "");
        if (hasPrivateIdentity(rawProfile)) {
            admissionProfiles.put(rawPlayer.getSessionId(), copy(rawProfile));
        }
        return publicPlayer(rawPlayer);
    }

    public ConnectPlayer publicPlayer(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        GameProfile publicProfile = BedrockIdentityProfiles.withoutEnvelope(player.getGameProfile());
        if (publicProfile == player.getGameProfile()) {
            return player;
        }
        return new ConnectPlayerImpl(
                player.getSessionId(),
                publicProfile,
                player.getAuth(),
                player.getLanguageTag());
    }

    Optional<GameProfile> takeAdmissionProfile(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        return Optional.ofNullable(admissionProfiles.remove(player.getSessionId()));
    }

    void clearClaims(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        identities.remove(player.getSessionId());
    }

    void record(ConnectPlayer player, BedrockIdentityClaims claims) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(claims, "claims");
        if (!player.getSessionId().equals(claims.getSessionId())) {
            throw new IllegalArgumentException("Bedrock identity claims session mismatch");
        }
        identities.put(player.getSessionId(), claims);
    }

    public Optional<BedrockIdentityClaims> get(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        return Optional.ofNullable(identities.get(player.getSessionId()));
    }

    public void remove(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        identities.remove(player.getSessionId());
        admissionProfiles.remove(player.getSessionId());
    }

    private static GameProfile copy(GameProfile profile) {
        return new GameProfile(
                profile.getUsername(),
                profile.getUniqueId(),
                Collections.unmodifiableList(new ArrayList<>(profile.getProperties())));
    }

    private static boolean hasPrivateIdentity(GameProfile profile) {
        return profile.getProperties().stream()
                .anyMatch(property ->
                        BedrockIdentityVerifier.PROPERTY_NAME.equals(property.getName()) ||
                                BedrockIdentityProfiles.SCOPE_PROPERTY_NAME.equals(property.getName()));
    }
}
