package com.minekube.connect.share.fabric.v26_2;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.util.List;
import java.util.UUID;

final class MinecraftGameProfileFactory {
  private MinecraftGameProfileFactory() {}

  static GameProfile create(UUID id, String username, List<Property> properties) {
    Multimap<String, Property> mapped = ArrayListMultimap.create();
    for (Property property : properties) {
      mapped.put(property.name(), property);
    }
    return new GameProfile(id, username, new PropertyMap(mapped));
  }
}
