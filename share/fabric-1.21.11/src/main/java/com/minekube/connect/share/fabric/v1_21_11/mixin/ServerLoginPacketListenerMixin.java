package com.minekube.connect.share.fabric.v1_21_11.mixin;

import com.mojang.authlib.GameProfile;
import com.minekube.connect.api.ConnectAttributes;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.network.netty.LocalSession;
import com.minekube.connect.share.admission.AdmissionAnswer;
import com.minekube.connect.share.fabric.v1_21_11.ConnectGameProfileMapper;
import com.minekube.connect.share.fabric.v1_21_11.Minecraft12111LoginAdmission;
import io.netty.channel.Channel;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private Connection connection;
    @Shadow @Nullable String requestedUsername;

    @Shadow
    abstract void startClientVerification(GameProfile profile);

    @Shadow
    public abstract void disconnect(Component reason);

    @Unique private boolean connectShare$admissionStarted;
    @Unique private boolean connectShare$admissionAllowed;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void connectShare$acceptConnectProfile(
            ServerboundHelloPacket hello,
            CallbackInfo callback) {
        Channel channel = ((ConnectionAccessor) connection).getConnectShareChannel();
        ConnectPlayer player = channel.attr(ConnectAttributes.CONNECT_PLAYER).get();
        if (player == null) {
            return;
        }

        GameProfile profile =
                ConnectGameProfileMapper.toMinecraftOrNull(player.getGameProfile());
        if (profile == null || !hello.name().equalsIgnoreCase(profile.name())) {
            disconnect(Component.literal("Connect identity is invalid"));
            callback.cancel();
            return;
        }

        requestedUsername = profile.name();
        startClientVerification(profile);
        callback.cancel();
    }

    @Inject(
            method = "verifyLoginAndFinishConnectionSetup",
            at = @At("HEAD"),
            cancellable = true)
    private void connectShare$awaitPassthroughAdmission(
            GameProfile profile,
            CallbackInfo callback) {
        Channel channel = ((ConnectionAccessor) connection).getConnectShareChannel();
        LocalSession.Context context = LocalSession.context(channel).orElse(null);
        if (context == null || !context.getPlayer().getAuth().isPassthrough()) {
            return;
        }
        if (connectShare$admissionAllowed) {
            return;
        }

        callback.cancel();
        if (connectShare$admissionStarted) {
            return;
        }
        connectShare$admissionStarted = true;
        CompletableFuture<AdmissionAnswer> decision = Minecraft12111LoginAdmission.request(
                        profile.name(),
                        profile.id(),
                        context.getPlayer().getSessionId(),
                        server.usesAuthentication() && !connection.isMemoryConnection())
                .toCompletableFuture();
        channel.closeFuture().addListener(ignored -> decision.cancel(false));
        decision.whenComplete((answer, failure) -> server.execute(() -> {
            if (!connection.isConnected()) {
                return;
            }
            if (failure != null || answer != AdmissionAnswer.ALLOW) {
                disconnect(connectShare$denialReason(answer));
                return;
            }
            connectShare$admissionAllowed = true;
        }));
    }

    @Unique
    private Component connectShare$denialReason(@Nullable AdmissionAnswer answer) {
        if (answer == AdmissionAnswer.TIMEOUT) {
            return Component.literal("Host approval timed out");
        }
        if (answer == AdmissionAnswer.CAPACITY) {
            return Component.literal("This share is full");
        }
        if (answer == AdmissionAnswer.STOPPED) {
            return Component.literal("Sharing stopped");
        }
        return Component.literal("Host denied this connection");
    }
}
