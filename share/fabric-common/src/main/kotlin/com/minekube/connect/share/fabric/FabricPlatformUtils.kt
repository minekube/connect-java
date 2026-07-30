package com.minekube.connect.share.fabric

import com.minekube.connect.platform.util.PlatformUtils

class FabricPlatformUtils(
    private val minecraftVersion: String,
    private val playerCount: () -> Int,
) : PlatformUtils() {
    override fun authType(): AuthType = AuthType.OFFLINE

    override fun minecraftVersion(): String = minecraftVersion

    override fun serverImplementationName(): String = "Minecraft integrated server"

    override fun getPlayerCount(): Int = playerCount()
}
