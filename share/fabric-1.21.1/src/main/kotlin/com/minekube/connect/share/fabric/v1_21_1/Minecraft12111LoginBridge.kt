package com.minekube.connect.share.fabric.v1_21_1

import com.mojang.authlib.GameProfile
import com.minekube.connect.api.ConnectAttributes
import com.minekube.connect.network.netty.LocalSession
import com.minekube.connect.share.admission.AdmissionAnswer
import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.share.direct.DirectSessionAttributes
import com.minekube.connect.share.fabric.DirectMinecraftAuthentication
import com.minekube.connect.share.fabric.FabricDirectAuthenticationPolicy
import com.minekube.connect.share.fabric.FabricLoginAdmissionRegistry
import com.minekube.connect.share.fabric.ui.ShareLoginMessages
import com.minekube.connect.tunnel.p2p.DirectP2pRoute
import com.minekube.connect.tunnel.p2p.DirectP2pSession
import com.minekube.connect.share.fabric.v1_21_1.mixin.ConnectionAccessor
import java.util.function.Consumer
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.core.UUIDUtil
import net.minecraft.util.StringUtil

object Minecraft1211LoginBridge {
    @JvmStatic
    fun hasConnectIdentity(connection: Connection): Boolean =
        channel(connection).attr(ConnectAttributes.CONNECT_PLAYER).get() != null

    @JvmStatic
    fun authenticatedProfile(
        connection: Connection,
        requestedName: String,
    ): GameProfile? {
        val player = channel(connection)
            .attr(ConnectAttributes.CONNECT_PLAYER)
            .get()
            ?: return null
        val profile = ConnectGameProfileMapper
            .toMinecraft(player.gameProfile)
            .getOrNull()
            ?: return null
        return profile.takeIf {
            requestedName.equals(profile.name, ignoreCase = true)
        }
    }

    @JvmStatic
    fun isPassthroughConnect(connection: Connection): Boolean =
        LocalSession.context(channel(connection))
            .map { it.player.auth.isPassthrough }
            .orElse(false)

    @JvmStatic
    fun hasDirectSession(connection: Connection): Boolean =
        directSession(connection) != null

    @JvmStatic
    fun shouldUseOfflineDirectProfile(connection: Connection): Boolean =
        directSession(connection)?.let {
            FabricDirectAuthenticationPolicy.minecraftAuthentication(
                it.authMode(),
            ) == DirectMinecraftAuthentication.OFFLINE_PROFILE
        } == true

    @JvmStatic
    fun offlineProfile(requestedName: String): GameProfile? =
        requestedName.takeIf(StringUtil::isValidPlayerName)
            ?.let(UUIDUtil::createOfflineProfile)

    @JvmStatic
    fun requestPassthroughAdmission(
        connection: Connection,
        server: MinecraftServer,
        profile: GameProfile,
        allow: Runnable,
        deny: Consumer<Component>,
    ) {
        val channel = channel(connection)
        val context = LocalSession.context(channel).orElse(null)
        if (context == null || !context.player.auth.isPassthrough) {
            server.execute(allow)
            return
        }
        val decision = FabricLoginAdmissionRegistry.request(
            name = profile.name,
            uuid = profile.id,
            connectionId = context.player.sessionId,
            minecraftAuthenticated =
                server.usesAuthentication() && !connection.isMemoryConnection,
            ingress = Ingress.CONNECT,
        ).toCompletableFuture()
        channel.closeFuture().addListener {
            decision.cancel(false)
        }
        decision.whenComplete { answer, failure ->
            server.execute {
                if (!connection.isConnected) {
                    return@execute
                }
                if (failure == null && answer == AdmissionAnswer.ALLOW) {
                    allow.run()
                } else {
                    deny.accept(denialReason(answer))
                }
            }
        }
    }

    @JvmStatic
    fun requestDirectAdmission(
        connection: Connection,
        server: MinecraftServer,
        profile: GameProfile,
        allow: Runnable,
        deny: Consumer<Component>,
    ) {
        val channel = channel(connection)
        val session = directSession(connection)
        if (session == null) {
            server.execute(allow)
            return
        }
        val minecraftAuthenticated =
            server.usesAuthentication() && !connection.isMemoryConnection
        FabricDirectAuthenticationPolicy.validate(
            session.authMode(),
            minecraftAuthenticated,
        ).onLeft {
            server.execute {
                deny.accept(
                    Component.literal(
                        ShareLoginMessages.AUTHENTICATION_REQUIRED.fallback,
                    ),
                )
            }
            return
        }
        val decision = FabricLoginAdmissionRegistry.request(
            name = profile.name,
            uuid = profile.id,
            connectionId = session.connectionId(),
            minecraftAuthenticated = minecraftAuthenticated,
            ingress = session.route().toIngress(),
            directPeerId = session.peerId(),
        ).toCompletableFuture()
        channel.closeFuture().addListener {
            decision.cancel(false)
        }
        decision.whenComplete { answer, failure ->
            server.execute {
                if (!connection.isConnected) {
                    return@execute
                }
                if (failure == null && answer == AdmissionAnswer.ALLOW) {
                    allow.run()
                } else {
                    deny.accept(denialReason(answer))
                }
            }
        }
    }

    private fun channel(connection: Connection) =
        (connection as ConnectionAccessor).connectShareChannel

    private fun directSession(connection: Connection): DirectP2pSession? =
        channel(connection).attr(DirectSessionAttributes.SESSION).get()

    private fun DirectP2pRoute.toIngress(): Ingress = when (this) {
        DirectP2pRoute.LAN -> Ingress.DIRECT_LAN
        DirectP2pRoute.INTERNET -> Ingress.DIRECT_INTERNET
    }

    private fun denialReason(answer: AdmissionAnswer?): Component =
        Component.literal(ShareLoginMessages.denial(answer).fallback)
}
