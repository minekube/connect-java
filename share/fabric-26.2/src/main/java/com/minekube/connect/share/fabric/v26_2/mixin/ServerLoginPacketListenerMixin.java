package com.minekube.connect.share.fabric.v26_2.mixin;

import com.mojang.authlib.GameProfile;
import com.minekube.connect.share.fabric.v26_2.Minecraft262LoginBridge;
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
    @Shadow private @Nullable String requestedUsername;

    @Shadow
    private void startClientVerification(GameProfile profile) {
        throw new AssertionError();
    }

    @Shadow
    public abstract void disconnect(Component reason);

    @Unique private boolean connectShare$admissionStarted;
    @Unique private boolean connectShare$admissionAllowed;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void connectShare$acceptConnectProfile(
            ServerboundHelloPacket hello,
            CallbackInfo callback) {
        if (!Minecraft262LoginBridge.hasConnectIdentity(connection)) {
            return;
        }

        GameProfile profile = Minecraft262LoginBridge.authenticatedProfile(
                connection, hello.name());
        if (profile == null) {
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
        if (!Minecraft262LoginBridge.isPassthroughConnect(connection)) {
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
        Minecraft262LoginBridge.requestPassthroughAdmission(
                connection,
                server,
                profile,
                () -> connectShare$admissionAllowed = true,
                this::disconnect);
    }
}
