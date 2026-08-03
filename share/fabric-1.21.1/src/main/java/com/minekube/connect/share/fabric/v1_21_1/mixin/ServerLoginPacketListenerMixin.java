package com.minekube.connect.share.fabric.v1_21_1.mixin;

import com.mojang.authlib.GameProfile;
import com.minekube.connect.share.fabric.v1_21_1.Minecraft1211LoginBridge;
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
        if (!Minecraft1211LoginBridge.hasConnectIdentity(connection)) {
            if (Minecraft1211LoginBridge.shouldUseOfflineDirectProfile(connection)) {
                GameProfile profile = Minecraft1211LoginBridge.offlineProfile(hello.name());
                if (profile == null) {
                    disconnect(Component.literal("Invalid characters in username"));
                } else {
                    requestedUsername = profile.getName();
                    startClientVerification(profile);
                }
                callback.cancel();
            }
            return;
        }

        GameProfile profile = Minecraft1211LoginBridge.authenticatedProfile(
                connection, hello.name());
        if (profile == null) {
            disconnect(Component.literal("Connect identity is invalid"));
            callback.cancel();
            return;
        }

        requestedUsername = profile.getName();
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
        boolean direct = Minecraft1211LoginBridge.hasDirectSession(connection);
        if (!direct && !Minecraft1211LoginBridge.isPassthroughConnect(connection)) {
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
        if (direct) {
            Minecraft1211LoginBridge.requestDirectAdmission(
                    connection,
                    server,
                    profile,
                    () -> connectShare$admissionAllowed = true,
                    this::disconnect);
        } else {
            Minecraft1211LoginBridge.requestPassthroughAdmission(
                    connection,
                    server,
                    profile,
                    () -> connectShare$admissionAllowed = true,
                    this::disconnect);
        }
    }
}
