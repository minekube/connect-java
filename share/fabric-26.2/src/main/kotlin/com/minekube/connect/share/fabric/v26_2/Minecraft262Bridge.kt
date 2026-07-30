package com.minekube.connect.share.fabric.v26_2

import com.minekube.connect.share.LocalShareChannel as CommonLocalShareChannel
import com.minekube.connect.share.LocalShareChannelBinder as CommonLocalShareChannelBinder
import com.minekube.connect.share.MinecraftVersionTransport
import com.minekube.connect.share.NettyLocalShareChannelBinder
import com.minekube.connect.share.PublishedMinecraftTransport as CommonPublishedMinecraftTransport
import com.minekube.connect.share.VersionedMinecraftBridge
import com.minekube.connect.share.fabric.FabricLocalLoginAdmissionGate
import com.minekube.connect.share.fabric.FabricLoginAdmissionRegistry

class Minecraft262Bridge internal constructor(
    transport: Minecraft262Transport,
    localBinder: LocalShareChannelBinder,
    loginAdmissionFactory: (() -> FabricLocalLoginAdmissionGate)? = null,
) : VersionedMinecraftBridge(
    transport = transport,
    localBinder = localBinder,
    loginAdmissionAcquire = loginAdmissionFactory?.let { factory ->
        {
            FabricLoginAdmissionRegistry.install(factory())
        }
    },
) {
    constructor() : this(
        VanillaMinecraft262Transport(),
        NettyLocalShareChannelBinder(),
    )

    constructor(
        loginAdmissionFactory: () -> FabricLocalLoginAdmissionGate,
    ) : this(
        VanillaMinecraft262Transport(),
        NettyLocalShareChannelBinder(),
        loginAdmissionFactory,
    )
}

internal typealias Minecraft262Transport = MinecraftVersionTransport
internal typealias PublishedMinecraftTransport = CommonPublishedMinecraftTransport
internal typealias LocalShareChannelBinder = CommonLocalShareChannelBinder
internal typealias LocalShareChannel = CommonLocalShareChannel
