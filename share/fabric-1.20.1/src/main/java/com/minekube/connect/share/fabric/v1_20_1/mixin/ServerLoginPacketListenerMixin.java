package com.minekube.connect.share.fabric.v1_20_1.mixin;

import com.mojang.authlib.GameProfile;
import com.minekube.connect.share.fabric.v1_20_1.Minecraft1201LoginBridge;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
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
    @Shadow private GameProfile gameProfile;

    @Shadow
    public abstract void handleAcceptedLogin();

    @Shadow
    public abstract void disconnect(Component reason);

    @Unique private boolean connectShare$admissionStarted;
    @Unique private boolean connectShare$admissionAllowed;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void connectShare$acceptConnectProfile(
            ServerboundHelloPacket hello,
            CallbackInfo callback) {
        GameProfile profile = null;
        if (Minecraft1201LoginBridge.hasConnectIdentity(connection)) {
            profile = Minecraft1201LoginBridge.authenticatedProfile(connection, hello.name());
            if (profile == null) {
                disconnect(Component.literal("Connect identity is invalid"));
                callback.cancel();
                return;
            }
        } else if (Minecraft1201LoginBridge.shouldUseOfflineDirectProfile(connection)) {
            profile = Minecraft1201LoginBridge.offlineProfile(hello.name());
            if (profile == null) {
                disconnect(Component.literal("Invalid characters in username"));
                callback.cancel();
                return;
            }
        }
        if (profile == null) {
            return;
        }

        gameProfile = profile;
        if (Minecraft1201LoginBridge.hasDirectSession(connection)
                || Minecraft1201LoginBridge.isPassthroughConnect(connection)) {
            connectShare$beginAdmission(profile);
        } else {
            connectShare$admissionAllowed = true;
            Minecraft1201LoginBridge.continueApprovedLogin(
                    this,
                    this::handleAcceptedLogin);
        }
        callback.cancel();
    }

    @Inject(method = "handleAcceptedLogin", at = @At("HEAD"), cancellable = true)
    private void connectShare$awaitAdmission(CallbackInfo callback) {
        boolean direct = Minecraft1201LoginBridge.hasDirectSession(connection);
        if (!direct && !Minecraft1201LoginBridge.isPassthroughConnect(connection)) {
            return;
        }
        if (connectShare$admissionAllowed) {
            return;
        }
        callback.cancel();
        connectShare$beginAdmission(gameProfile);
    }

    @Unique
    private void connectShare$beginAdmission(GameProfile profile) {
        if (connectShare$admissionStarted) {
            return;
        }
        connectShare$admissionStarted = true;
        Runnable allow = () -> {
            connectShare$admissionAllowed = true;
            Minecraft1201LoginBridge.continueApprovedLogin(
                    this,
                    this::handleAcceptedLogin);
        };
        if (Minecraft1201LoginBridge.hasDirectSession(connection)) {
            Minecraft1201LoginBridge.requestDirectAdmission(
                    connection, server, profile, allow, this::disconnect);
        } else {
            Minecraft1201LoginBridge.requestPassthroughAdmission(
                    connection, server, profile, allow, this::disconnect);
        }
    }
}
