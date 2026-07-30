package com.minekube.connect.share.fabric.v1_21_11

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
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
        val properties = source.properties.map { property ->
            ensure(property.name.isNotBlank() && property.value.isNotBlank()) {
                ProfileMappingFailure.InvalidProperty
            }
            val signature = property.signature?.takeIf(String::isNotEmpty)
            if (signature == null) {
                Property(property.name, property.value)
            } else {
                Property(property.name, property.value, signature)
            }
        }
        MinecraftGameProfileFactory.create(
            source.uniqueId,
            source.username,
            properties,
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
