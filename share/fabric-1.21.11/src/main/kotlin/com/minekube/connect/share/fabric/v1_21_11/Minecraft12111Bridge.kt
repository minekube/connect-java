package com.minekube.connect.share.fabric.v1_21_11

import com.minekube.connect.share.LocalShareChannel as CommonLocalShareChannel
import com.minekube.connect.share.LocalShareChannelBinder as CommonLocalShareChannelBinder
import com.minekube.connect.share.MinecraftVersionTransport
import com.minekube.connect.share.NettyLocalShareChannelBinder
import com.minekube.connect.share.PublishedMinecraftTransport as CommonPublishedMinecraftTransport
import com.minekube.connect.share.ShareConnectionGateway
import com.minekube.connect.share.VersionedMinecraftBridge
import com.minekube.connect.share.CaptureLease as CommonCaptureLease
import com.minekube.connect.share.CapturedServerTransport as CommonCapturedServerTransport
import com.minekube.connect.share.CapturedTransport as CommonCapturedTransport
import com.minekube.connect.share.fabric.FabricLoginAdmissionRegistry
import com.minekube.connect.share.fabric.FabricLocalLoginAdmissionGate

class Minecraft12111Bridge internal constructor(
    transport: Minecraft12111Transport,
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
        VanillaMinecraft12111Transport(),
        NettyLocalShareChannelBinder(),
    )

    constructor(
        loginAdmissionFactory: () -> FabricLocalLoginAdmissionGate,
    ) : this(
        VanillaMinecraft12111Transport(),
        NettyLocalShareChannelBinder(),
        loginAdmissionFactory,
    )
}

internal class GatewayMinecraft12111Bridge(
    gateway: ShareConnectionGateway,
    loginAdmissionFactory: () -> FabricLocalLoginAdmissionGate,
) : VersionedMinecraftBridge(
    transport = VanillaMinecraft12111Transport(),
    gateway = gateway,
    loginAdmissionAcquire = {
        FabricLoginAdmissionRegistry.install(
            loginAdmissionFactory(),
        )
    },
)

internal typealias Minecraft12111Transport = MinecraftVersionTransport
internal typealias PublishedMinecraftTransport = CommonPublishedMinecraftTransport
internal typealias LocalShareChannelBinder = CommonLocalShareChannelBinder
internal typealias LocalShareChannel = CommonLocalShareChannel
internal typealias CapturedServerTransport = CommonCapturedServerTransport
internal typealias CaptureLease = CommonCaptureLease
internal typealias CapturedTransport = CommonCapturedTransport
