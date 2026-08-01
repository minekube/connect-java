package com.minekube.connect.share.fabric.v26_2

import com.minekube.connect.share.fabric.ConnectShareClient
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class SharePrivacyScreen(
    private val parent: Screen,
) : Screen(Component.translatable("connect_share.privacy.title")) {
    private val viewModel = ConnectShareClient.viewModel()
    private var diagnosticsCopied = false

    override fun init() {
        addRenderableWidget(
            MultiLineTextWidget(
                width / 2 - 155,
                18,
                Component.translatable("connect_share.privacy.description"),
                font,
            ).setMaxWidth(310).setCentered(true),
        )
        val privacy = viewModel.state.value.presencePrivacy
        privacyToggle("online", 66, privacy.showOnline) { value ->
            viewModel.setPresencePrivacy(
                viewModel.state.value.presencePrivacy.copy(showOnline = value),
            )
        }
        privacyToggle("playing", 92, privacy.showPlaying) { value ->
            viewModel.setPresencePrivacy(
                viewModel.state.value.presencePrivacy.copy(showPlaying = value),
            )
        }
        privacyToggle(
            "current_server",
            118,
            privacy.showCurrentServer,
        ) { value ->
            viewModel.setPresencePrivacy(
                viewModel.state.value.presencePrivacy.copy(
                    showCurrentServer = value,
                ),
            )
        }
        privacyToggle("joinable", 144, privacy.showJoinable) { value ->
            viewModel.setPresencePrivacy(
                viewModel.state.value.presencePrivacy.copy(showJoinable = value),
            )
        }
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
            }.bounds(width / 2 - 75, height - 76, 150, 20).build(),
        )
        addRenderableWidget(
            Button.builder(
                Component.translatable(
                    "connect_share.privacy.blocked",
                    ConnectShareClient.friendsViewModel().state.value.blocked.size,
                ),
            ) {
                minecraft.gui.setScreen(BlockedFriendsScreen(this))
            }.bounds(width / 2 - 75, height - 52, 150, 20).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(width / 2 - 75, height - 28, 150, 20)
                .build(),
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
        addRenderableWidget(
            Checkbox.builder(
                Component.translatable("connect_share.privacy.$key"),
                font,
            ).pos(width / 2 - 155, y)
                .selected(selected)
                .onValueChange { _, value -> changed(value) }
                .build(),
        )
    }
}
