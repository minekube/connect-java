package com.minekube.connect.share.fabric.v1_21_1.mixin;

import com.minekube.connect.share.fabric.ConnectShareClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.gui.screens.TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    @Unique private Button connectShare$friendsButton;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void connectShare$addJoinButton(CallbackInfo ci) {
        if (!ConnectShareClient.isInstalled()) {
            return;
        }
        connectShare$friendsButton = addRenderableWidget(
                Button.builder(
                                connectShare$friendsLabel(),
                                button -> ConnectShareClient.openJoinScreen(this))
                        .bounds(width - 74, 4, 70, 20)
                        .build());
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void connectShare$refreshJoinButton(CallbackInfo ci) {
        if (connectShare$friendsButton != null) {
            connectShare$friendsButton.setMessage(connectShare$friendsLabel());
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
