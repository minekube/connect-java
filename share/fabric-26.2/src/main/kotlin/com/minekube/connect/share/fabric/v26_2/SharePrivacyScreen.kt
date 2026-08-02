package com.minekube.connect.share.fabric.v26_2

import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.ui.AdaptiveShareLayout
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class SharePrivacyScreen(
    private val parent: Screen,
) : Screen(Component.translatable("connect_share.privacy.title")) {
    private val viewModel = ConnectShareClient.viewModel()
    private var diagnosticsCopied = false

    override fun init() {
        val layout = AdaptiveShareLayout.form(width, height, 4)
        addRenderableWidget(
            centered(title.copy().withStyle(ChatFormatting.BOLD), layout.headerY),
        )
        addRenderableWidget(
            MultiLineTextWidget(
                layout.contentX,
                layout.subtitleY,
                Component.translatable("connect_share.privacy.description"),
                font,
            ).setMaxWidth(layout.contentWidth).setCentered(true),
        )

        val privacy = viewModel.state.value.presencePrivacy
        privacyToggle("online", layout.bodyTop, privacy.showOnline) { value ->
            viewModel.setPresencePrivacy(
                viewModel.state.value.presencePrivacy.copy(showOnline = value),
            )
        }
        privacyToggle(
            "playing",
            layout.bodyTop + 24,
            privacy.showPlaying,
        ) { value ->
            viewModel.setPresencePrivacy(
                viewModel.state.value.presencePrivacy.copy(showPlaying = value),
            )
        }
        privacyToggle(
            "current_server",
            layout.bodyTop + 48,
            privacy.showCurrentServer,
        ) { value ->
            viewModel.setPresencePrivacy(
                viewModel.state.value.presencePrivacy.copy(
                    showCurrentServer = value,
                ),
            )
        }
        privacyToggle(
            "joinable",
            layout.bodyTop + 72,
            privacy.showJoinable,
        ) { value ->
            viewModel.setPresencePrivacy(
                viewModel.state.value.presencePrivacy.copy(showJoinable = value),
            )
        }
        addRenderableWidget(
            MultiLineTextWidget(
                layout.contentX,
                layout.bodyTop + 104,
                Component.translatable("connect_share.privacy.confirmed_only")
                    .withStyle(ChatFormatting.GRAY),
                font,
            ).setMaxWidth(layout.contentWidth).setCentered(true),
        )

        addRenderableWidget(
            Button.builder(
                Component.translatable(
                    if (diagnosticsCopied) {
                        "connect_share.diagnostics.copied"
                    } else {
                        "connect_share.diagnostics.copy"
                    },
                ),
            ) {
                minecraft.keyboardHandler.setClipboard(
                    ConnectShareClient.diagnosticBundle(),
                )
                diagnosticsCopied = true
                rebuildWidgets()
            }.bounds(
                layout.contentX,
                layout.footerTop,
                layout.halfButtonWidth,
                20,
            ).build(),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable(
                    "connect_share.privacy.blocked",
                    ConnectShareClient.friendsViewModel()
                        .state.value.blocked.size,
                ),
            ) {
                minecraft.gui.setScreen(BlockedFriendsScreen(this))
            }.bounds(
                layout.contentX + layout.halfButtonWidth + 6,
                layout.footerTop,
                layout.halfButtonWidth,
                20,
            ).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(
                    layout.contentX,
                    layout.footerTop + 24,
                    layout.contentWidth,
                    20,
                ).build(),
        )
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    private fun privacyToggle(
        key: String,
        y: Int,
        selected: Boolean,
        changed: (Boolean) -> Unit,
    ) {
        val layout = AdaptiveShareLayout.form(width, height, 4)
        addRenderableWidget(
            Checkbox.builder(
                Component.translatable("connect_share.privacy.$key"),
                font,
            ).pos(layout.contentX, y)
                .selected(selected)
                .onValueChange { _, value -> changed(value) }
                .build(),
        )
    }

    private fun centered(message: Component, y: Int): StringWidget {
        val textWidth = font.width(message)
        return StringWidget(
            width / 2 - textWidth / 2,
            y,
            textWidth,
            11,
            message,
            font,
        )
    }
}
