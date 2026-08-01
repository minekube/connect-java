package com.minekube.connect.share.fabric.v1_21_1.mixin;

import com.minekube.connect.share.fabric.ConnectShareClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.gui.screens.PauseScreen.class)
abstract class PauseScreenMixin extends Screen {
    @Shadow @Final private boolean showPauseMenu;
    @Shadow private @Nullable Button disconnectButton;
    @Unique private @Nullable Button connectShareButton;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void connectShare$addButton(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (!showPauseMenu
                || !client.hasSingleplayerServer()
                || disconnectButton == null
                || !ConnectShareClient.isInstalled()) {
            return;
        }

        int shareY = disconnectButton.getY();
        disconnectButton.setY(shareY + 24);
        connectShareButton = addRenderableWidget(
                Button.builder(
                                Component.translatable(
                                        ConnectShareClient.pauseButtonTranslationKey()),
                                button -> ConnectShareClient.openPauseScreen(this))
                        .bounds(disconnectButton.getX(), shareY, 204, 20)
                        .build());
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void connectShare$refreshButton(CallbackInfo ci) {
        if (connectShareButton != null) {
            connectShareButton.setMessage(
                    Component.translatable(ConnectShareClient.pauseButtonTranslationKey()));
        }
    }
}
