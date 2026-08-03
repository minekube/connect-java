package com.minekube.connect.share.fabric

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode

data object DirectOnlineAuthenticationRequired {
    const val SAFE_MESSAGE =
        "This direct guest requested online authentication, but Minecraft did not verify it"
}

enum class DirectMinecraftAuthentication {
    MOJANG,
    OFFLINE_PROFILE,
}

object FabricDirectAuthenticationPolicy {
    fun minecraftAuthentication(
        requestedMode: DirectP2pAuthMode,
    ): DirectMinecraftAuthentication = when (requestedMode) {
        DirectP2pAuthMode.ONLINE -> DirectMinecraftAuthentication.MOJANG
        DirectP2pAuthMode.OFFLINE ->
            DirectMinecraftAuthentication.OFFLINE_PROFILE
    }

    fun validate(
        requestedMode: DirectP2pAuthMode,
        minecraftAuthenticated: Boolean,
    ): Either<DirectOnlineAuthenticationRequired, Unit> = either {
        ensure(
            requestedMode != DirectP2pAuthMode.ONLINE ||
                minecraftAuthenticated,
        ) {
            DirectOnlineAuthenticationRequired
        }
    }
}
