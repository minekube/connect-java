package com.minekube.connect.share.fabric.v1_21_11

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.google.common.collect.ArrayListMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import com.minekube.connect.api.player.GameProfile as ConnectGameProfile
import net.minecraft.util.StringUtil

object ConnectGameProfileMapper {
    fun toMinecraft(
        source: ConnectGameProfile,
    ): Either<ProfileMappingFailure, GameProfile> = either {
        ensure(
            source.username.isNotBlank() &&
                StringUtil.isValidPlayerName(source.username),
        ) {
            ProfileMappingFailure.InvalidName
        }
        val properties = ArrayListMultimap.create<String, Property>()
        source.properties.forEach { property ->
            ensure(property.name.isNotBlank() && property.value.isNotBlank()) {
                ProfileMappingFailure.InvalidProperty
            }
            val signature = property.signature?.takeIf(String::isNotEmpty)
            val mapped = if (signature == null) {
                Property(property.name, property.value)
            } else {
                Property(property.name, property.value, signature)
            }
            properties.put(property.name, mapped)
        }
        GameProfile(
            source.uniqueId,
            source.username,
            PropertyMap(properties),
        )
    }

    @JvmStatic
    fun toMinecraftOrNull(source: ConnectGameProfile): GameProfile? =
        toMinecraft(source).getOrNull()
}

sealed interface ProfileMappingFailure {
    data object InvalidName : ProfileMappingFailure

    data object InvalidProperty : ProfileMappingFailure
}
