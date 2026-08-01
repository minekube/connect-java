package com.minekube.connect.share.fabric.v1_20_1;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.util.List;
import java.util.UUID;

final class MinecraftGameProfileFactory {
  private MinecraftGameProfileFactory() {}

  static GameProfile create(UUID id, String username, List<Property> properties) {
    GameProfile profile = new GameProfile(id, username);
    for (Property property : properties) {
      profile.getProperties().put(property.getName(), property);
    }
    return profile;
  }
}
