package com.minekube.connect.share.fabric.v1_21_11.mixin;

import com.minekube.connect.share.fabric.ConnectShareClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.gui.screens.TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void connectShare$addJoinButton(CallbackInfo ci) {
        if (!ConnectShareClient.isInstalled()) {
            return;
        }
        addRenderableWidget(
                Button.builder(
                                Component.translatable("connect_share.menu.join"),
                                button -> ConnectShareClient.openJoinScreen(this))
                        .bounds(width - 106, 4, 102, 20)
                        .build());
    }
}
