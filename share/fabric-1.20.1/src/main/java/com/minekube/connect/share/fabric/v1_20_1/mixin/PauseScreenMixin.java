package com.minekube.connect.share.fabric.v1_20_1.mixin;

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
    @Unique private @Nullable Button connectShareFriendsButton;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void connectShare$addButtons(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (!showPauseMenu
                || disconnectButton == null
                || !ConnectShareClient.isInstalled()) {
            return;
        }

        int rowX = disconnectButton.getX();
        int rowY = disconnectButton.getY();
        disconnectButton.setY(rowY + 24);
        removeWidget(disconnectButton);
        if (client.hasSingleplayerServer()) {
            connectShareButton = addRenderableWidget(
                    Button.builder(
                                    Component.translatable(
                                            ConnectShareClient.pauseButtonTranslationKey()),
                                    button -> ConnectShareClient.openPauseScreen(this))
                            .bounds(rowX, rowY, 100, 20)
                            .build());
            connectShareFriendsButton = addRenderableWidget(
                    Button.builder(
                                    connectShare$friendsLabel(),
                                    button -> ConnectShareClient.openJoinScreen(this))
                            .bounds(rowX + 104, rowY, 100, 20)
                            .build());
        } else {
            connectShareFriendsButton = addRenderableWidget(
                    Button.builder(
                                    connectShare$friendsLabel(),
                                    button -> ConnectShareClient.openJoinScreen(this))
                            .bounds(rowX, rowY, 204, 20)
                            .build());
        }
        addRenderableWidget(disconnectButton);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void connectShare$refreshButtons(CallbackInfo ci) {
        if (connectShareButton != null) {
            connectShareButton.setMessage(
                    Component.translatable(ConnectShareClient.pauseButtonTranslationKey()));
        }
        if (connectShareFriendsButton != null) {
            connectShareFriendsButton.setMessage(connectShare$friendsLabel());
        }
    }

    @Unique
    private Component connectShare$friendsLabel() {
        int count = ConnectShareClient.friendsButtonCount();
        String key = ConnectShareClient.friendsButtonTranslationKey();
        return count > 0
                ? Component.translatable(key, count)
                : Component.translatable(key);
    }
}
